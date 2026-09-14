package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real stack against a real PostgreSQL and the in-process LDAP directory: sign-in,
 * the roles that come from LDAP groups, Flyway migrations and the database's own checks, server-side
 * composition from the catalogue, the jsonb mapping, payload-inclusive search and the size limits. Requires a working Docker daemon.
 */
@SpringBootTest(properties = {
        "app.seed-examples=false",
        // Room for a fully composed scenario, so the limits are reached on purpose rather than by accident.
        "app.limits.max-payload-bytes=10000",
        "app.limits.max-request-bytes=30000",
        // A free port, so the suite runs whether or not the application is already running locally.
        "spring.ldap.embedded.port=0",
})
@AutoConfigureMockMvc
@Testcontainers
class ProfileIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    com.nest.jsonstore.template.TemplateComposer composer;

    /** Signs in against the embedded directory and returns the bearer token. */
    private String tokenFor(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"secret"}""".formatted(username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** Creates a profile as alice and returns its id. */
    private String create(String token, String body) throws Exception {
        String response = mockMvc.perform(as(post("/api/profiles"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void refusesAnyoneWithoutAToken() throws Exception {
        // A caller with no token is told so in the same error shape every other endpoint uses,
        // and in the header RFC 6750 defines for a bearer-token API.
        mockMvc.perform(get("/api/profiles"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(as(get("/api/profiles"), "not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong username or password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    void handsOutABearerTokenTheStandardWay() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret"}"""))
                .andExpect(status().isOk())
                // Tokens are credentials, so no cache may keep a copy.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.roles", org.hamcrest.Matchers.hasItem("ADMINS")));
    }

    @Test
    void exchangesAValidTokenForANewOne() throws Exception {
        mockMvc.perform(as(post("/api/auth/refresh"), tokenFor("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("bob"));

        mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void readsGroupsFromTheDirectory() throws Exception {
        mockMvc.perform(as(get("/api/auth/me"), tokenFor("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("ADMINS", "DEVELOPERS")));

        mockMvc.perform(as(get("/api/auth/me"), tokenFor("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.contains("DEVELOPERS")));
    }

    /** The ETag a profile is currently served with, which a change must send back as If-Match. */
    private String etagOf(String token, String id) throws Exception {
        return mockMvc.perform(as(get("/api/profiles/{id}", id), token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
    }

    /** A profile built from the checkout scenario alone, with the given order reference. */
    private String checkout(String name, String orderRef) throws Exception {
        return profile(name, List.of(), Map.of("scenario", "checkout"), Map.of("orderRef", orderRef));
    }

    /** A create or update body: the templates chosen and the values typed, never the inputs themselves. */
    private String profile(String name, List<String> tags, Map<String, String> selection, Map<String, Object> values)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("tags", tags);
        body.put("template", Map.of("selection", selection, "values", values));
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void storesTheComposedInputsAsRealJsonbAndFindsThemByTheirContents() throws Exception {
        String alice = tokenFor("alice");

        String id = create(alice, profile("Topology", List.of("infra"),
                Map.of("scenario", "checkout"), Map.of("orderRef", "ORD-EU-WEST-7")));
        mockMvc.perform(as(get("/api/profiles/{id}", id), alice))
                .andExpect(jsonPath("$.sizeBytes", org.hamcrest.Matchers.greaterThan(0)));

        // Stored as a jsonb object, so PostgreSQL can read inside it.
        String type = jdbcTemplate.queryForObject(
                "select jsonb_typeof(payload -> 'orders-api') from profile where id = ?::uuid", String.class, id);
        String reference = jdbcTemplate.queryForObject(
                "select payload -> 'orders-api' -> 'body' ->> 'reference' from profile where id = ?::uuid", String.class, id);
        assertThat(type).isEqualTo("object");
        assertThat(reference).isEqualTo("ORD-EU-WEST-7");

        // Search reaches into the inputs, not just the name.
        mockMvc.perform(as(get("/api/profiles").param("search", "ORD-EU-WEST-7"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Topology"));

        // Deleting belongs to the admins group: bob is only a developer.
        mockMvc.perform(as(delete("/api/profiles/{id}", id), tokenFor("bob"))).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/profiles/{id}", id), alice).header(HttpHeaders.IF_MATCH, etagOf(alice, id)))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/profiles/{id}", id), alice)).andExpect(status().isNotFound());
    }

    /**
     * The server composes what it stores. Inputs sent alongside the template are ignored, the stored
     * template records every value that built them — defaults included — and a standalone placeholder
     * keeps its field's type.
     */
    @Test
    void composesTheInputsItselfAndIgnoresAnyTheClientSends() throws Exception {
        String alice = tokenFor("alice");

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Composed","payload":{"main":{"forged":true}},
                                 "template":{"selection":{"scenario":"checkout","payment":"card-approved"},
                                             "values":{"scenarioName":"Composed","quantity":2}}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.main").doesNotExist())
                .andExpect(jsonPath("$.payload['orders-api'].body.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.payload.payments").exists())
                .andExpect(jsonPath("$.template.selection.payment").value("card-approved"))
                .andExpect(jsonPath("$.template.values.quantity").value(2))
                // Not typed, so the catalogue's default was used — and recorded.
                .andExpect(jsonPath("$.template.values.currency").value("EUR"));
    }

    /**
     * Anything the catalogue would not have built is refused with 422 and every problem at once, each
     * naming the part of the request it is about, so the form can mark them all in one go.
     */
    @Test
    void refusesInputsTheCatalogueWouldNotBuild() throws Exception {
        String alice = tokenFor("alice");

        // No template at all: there is nothing to build the inputs from.
        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bare","payload":{"main":{"a":1}}}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Invalid inputs"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("template"));

        // The required group left unset, a template that does not exist, and one from the wrong group.
        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(profile("Selection", List.of(),
                                Map.of("payment", "card-sideways", "fulfilment", "email-only"), Map.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[*].field", org.hamcrest.Matchers.containsInAnyOrder(
                        "template.selection.payment", "template.selection.fulfilment", "template.selection.scenario")));

        // Values of the wrong kind, out of range, not among the options, blank but required, or for no field.
        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(profile("Values", List.of(), Map.of("scenario", "checkout"), Map.of(
                                "quantity", 500,
                                "unitPriceMinor", "a lot",
                                "currency", "XYZ",
                                "scenarioName", "  ",
                                "nobodyAskedFor", "x"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[*].field", org.hamcrest.Matchers.containsInAnyOrder(
                        "template.values.quantity", "template.values.unitPriceMinor", "template.values.currency",
                        "template.values.scenarioName", "template.values.nobodyAskedFor")))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'template.values.quantity')].message",
                        org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("at most 99"))));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from profile where name in ('Bare', 'Selection', 'Values')", Integer.class)).isZero();
    }

    /** Renaming or retagging a profile leaves its inputs and template exactly as they were. */
    @Test
    void changesOnlyTheDetailsWhenAnUpdateCarriesNoTemplate() throws Exception {
        String alice = tokenFor("alice");
        String id = create(alice, checkout("Before", "ORD-DETAILS-1"));

        mockMvc.perform(as(put("/api/profiles/{id}", id), alice).header(HttpHeaders.IF_MATCH, etagOf(alice, id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"After","description":"Renamed","tags":["renamed"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After"))
                .andExpect(jsonPath("$.tags[0]").value("renamed"))
                .andExpect(jsonPath("$.payload['orders-api'].body.reference").value("ORD-DETAILS-1"))
                .andExpect(jsonPath("$.template.selection.scenario").value("checkout"));
    }

    /**
     * Two people editing the same profile cannot silently overwrite each other. A change names the
     * version it was made to; one made to an out-of-date copy is refused, and says who saved since.
     */
    @Test
    void refusesAChangeMadeToAnOutOfDateCopy() throws Exception {
        String alice = tokenFor("alice");
        String bob = tokenFor("bob");
        String id = create(alice, checkout("Contended", "ORD-CONTENDED"));
        String loaded = etagOf(alice, id);
        assertThat(loaded).isEqualTo("\"0\"");

        // Saying nothing about the version is not allowed: the last save would simply win.
        mockMvc.perform(as(put("/api/profiles/{id}", id), bob).contentType(MediaType.APPLICATION_JSON)
                        .content(checkout("Contended", "ORD-BOB")))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.status").value(428));

        // alice saves the version she loaded; the new ETag comes back with the save.
        mockMvc.perform(as(put("/api/profiles/{id}", id), alice).header(HttpHeaders.IF_MATCH, loaded)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkout("Contended", "ORD-ALICE")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.version").value(1));

        // bob still holds version 0, so his save and his delete are both refused, naming alice.
        mockMvc.perform(as(put("/api/profiles/{id}", id), bob).header(HttpHeaders.IF_MATCH, loaded)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkout("Contended", "ORD-BOB")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("changed by alice")));
        mockMvc.perform(as(delete("/api/profiles/{id}", id), alice).header(HttpHeaders.IF_MATCH, loaded))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(as(get("/api/profiles/{id}", id), alice))
                .andExpect(jsonPath("$.payload['orders-api'].body.reference").value("ORD-ALICE"));

        // Overwriting is still possible, but only by asking for it.
        mockMvc.perform(as(put("/api/profiles/{id}", id), bob).header(HttpHeaders.IF_MATCH, "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkout("Contended", "ORD-BOB")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy").value("bob"));

        // The list carries the version too, so a row can be deleted without opening it; a weak tag matches.
        mockMvc.perform(as(get("/api/profiles").param("search", "Contended"), alice))
                .andExpect(jsonPath("$.items[0].version").value(2));
        mockMvc.perform(as(delete("/api/profiles/{id}", id), alice).header(HttpHeaders.IF_MATCH, "W/\"2\""))
                .andExpect(status().isNoContent());
    }

    /** A browser on another origin can send If-Match and read the ETag. */
    @Test
    void letsABrowserOnAnotherOriginUseVersions() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/profiles/{id}", java.util.UUID.randomUUID())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5174")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "if-match,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsStringIgnoringCase("if-match")));

        mockMvc.perform(as(get("/api/profiles"), tokenFor("bob")).header(HttpHeaders.ORIGIN, "http://localhost:5174"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString("ETag")));
    }

    /** Tag filtering is exact, unlike the free-text search which would also match the inputs. */
    @Test
    void narrowsToOneTag() throws Exception {
        String alice = tokenFor("alice");

        create(alice, profile("Smoke one", List.of("smoke", "checkout"),
                Map.of("scenario", "checkout"), Map.of("scenarioName", "regression lives here too")));
        create(alice, profile("Regression one", List.of("regression"),
                Map.of("scenario", "checkout"), Map.of()));

        mockMvc.perform(as(get("/api/profiles").param("tag", "smoke"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Smoke one"));

        // The word appears in another profile's inputs, but the tag filter does not care.
        mockMvc.perform(as(get("/api/profiles").param("tag", "regression"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Regression one"));

        // Tag and search narrow together.
        mockMvc.perform(as(get("/api/profiles").param("tag", "checkout").param("search", "nothing-matches"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void rejectsInputsOverTheConfiguredLimit() throws Exception {
        // Small enough to be read, but it composes into inputs larger than the stored limit.
        mockMvc.perform(as(post("/api/profiles"), tokenFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profile("Too big", List.of(),
                                Map.of("scenario", "checkout", "expectations", "expect-failure"),
                                Map.of("failureNotes", "x".repeat(12_000)))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Payload too large"));
    }

    /** A profile can feed several systems, each with its own document. */
    @Test
    void storesOneDocumentPerSystem() throws Exception {
        String alice = tokenFor("alice");

        create(alice, profile("Two systems", List.of(),
                Map.of("scenario", "checkout", "payment", "card-approved"), Map.of("sku", "NEST-02")));

        mockMvc.perform(as(get("/api/profiles").param("search", "Two systems"), alice))
                .andExpect(status().isOk())
                // Sorted, because PostgreSQL does not keep the order the keys were written in.
                .andExpect(jsonPath("$.items[0].documents", org.hamcrest.Matchers.contains(
                        "assertions", "kafka-events", "orders-api", "payments")));
    }

    /**
     * The database refuses inputs that are not named documents or still hold a placeholder, whatever
     * writes them — the application is not the only thing with a connection string.
     */
    @Test
    void theDatabaseRefusesUnfilledOrMalformedInputs() throws Exception {
        String id = create(tokenFor("alice"), checkout("Guarded", "ORD-GUARD"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "update profile set payload = '{\"inventory\":{\"sku\":\"${sku}\"}}'::jsonb where id = ?::uuid", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "update profile set payload = '[1, 2, 3]'::jsonb where id = ?::uuid", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Stored inputs that no longer match their own template — written before the server composed them,
     * or before a catalogue fix — are rebuilt by an administrator. A dry run changes nothing.
     */
    @Test
    void rebuildsStaleInputsFromTheirTemplateWhenAnAdministratorAsks() throws Exception {
        String alice = tokenFor("alice");
        String stale = create(alice, checkout("Stale", "ORD-STALE"));
        String untemplated = create(alice, checkout("Untemplated", "ORD-UNTEMPLATED"));
        jdbcTemplate.update("update profile set payload = '{\"orders-api\":{\"stale\":true}}'::jsonb where id = ?::uuid", stale);
        jdbcTemplate.update("update profile set template = null where id = ?::uuid", untemplated);

        mockMvc.perform(as(post("/api/admin/profiles/recompose"), tokenFor("bob"))).andExpect(status().isForbidden());

        mockMvc.perform(as(post("/api/admin/profiles/recompose"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].outcome".formatted(stale), org.hamcrest.Matchers.contains("would-change")))
                .andExpect(jsonPath("$[?(@.id == '%s')].outcome".formatted(untemplated), org.hamcrest.Matchers.contains("not-templated")));
        mockMvc.perform(as(get("/api/profiles/{id}", stale), alice))
                .andExpect(jsonPath("$.payload['orders-api'].stale").value(true));

        mockMvc.perform(as(post("/api/admin/profiles/recompose").param("apply", "true"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].outcome".formatted(stale), org.hamcrest.Matchers.contains("repaired")));
        mockMvc.perform(as(get("/api/profiles/{id}", stale), alice))
                .andExpect(jsonPath("$.payload['orders-api'].stale").doesNotExist())
                .andExpect(jsonPath("$.payload['orders-api'].body.reference").value("ORD-STALE"));

        // A second run finds nothing left to repair.
        mockMvc.perform(as(post("/api/admin/profiles/recompose"), alice))
                .andExpect(jsonPath("$[?(@.id == '%s')].outcome".formatted(stale), org.hamcrest.Matchers.contains("unchanged")));
    }

    /**
     * The seeded examples are composed from the catalogue, so they demonstrate the multi-system
     * shape and cannot drift away from what the form would build.
     */
    @Test
    void seedsExamplesComposedFromTheCatalogue() {
        var composed = composer.compose(
                java.util.Map.of("scenario", "checkout", "payment", "card-declined"),
                java.util.Map.of("orderRef", "ORD-777"));

        // One scenario and one payment fragment already feed four systems.
        assertThat(composed.documents().fieldNames()).toIterable()
                .contains("orders-api", "kafka-events", "assertions", "payments");
        assertThat(composed.documents().at("/orders-api/body/reference").asText()).isEqualTo("ORD-777");
        assertThat(composed.documents().at("/kafka-events/key").asText()).isEqualTo("ORD-777");
        // A placeholder that stands alone keeps the field's own type.
        assertThat(composed.documents().at("/orders-api/body/lines/0/quantity").isNumber()).isTrue();

        assertThat(composed.documents().at("/payments/body/simulate").asText()).isEqualTo("declined");
        // The values that produced it are kept too, which is what the form reopens with.
        assertThat(composed.values().get("orderRef").asText()).isEqualTo("ORD-777");
    }

    @Test
    void rejectsAnIdThatIsNotAUuid() throws Exception {
        mockMvc.perform(as(get("/api/profiles/{id}", "not-a-uuid"), tokenFor("alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /** The composer needs the shape, not any particular fragment, so that is what is asserted. */
    @Test
    void servesTheTemplateCatalogue() throws Exception {
        mockMvc.perform(as(get("/api/templates"), tokenFor("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").isNotEmpty())
                .andExpect(jsonPath("$.groups[0].required").value(true))
                .andExpect(jsonPath("$.fragments[0].group").isNotEmpty())
                .andExpect(jsonPath("$.fragments[0].fields").isArray())
                // Each fragment writes one document per system it feeds.
                .andExpect(jsonPath("$.fragments[0].documents").isMap())
                .andExpect(jsonPath("$.documents").isArray());
    }

    /** A template must be a selection plus values; anything else is the wrong shape, named as such. */
    @Test
    void refusesATemplateThatIsNotASelectionPlusValues() throws Exception {
        String alice = tokenFor("alice");

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Odd","template":"checkout"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'template' has the wrong type"));

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Odd","template":{"selection":{"scenario":{"id":"checkout"}},"values":{}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    /** A search term is text, not a pattern: "100%" finds the characters, not everything. */
    @Test
    void treatsWildcardCharactersInASearchAsText() throws Exception {
        String alice = tokenFor("alice");
        create(alice, checkout("Discount 100%", "ORD-A"));
        create(alice, checkout("Discount 10", "ORD-B"));

        mockMvc.perform(as(get("/api/profiles").param("search", "100%"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Discount 100%"));

        // An underscore would otherwise match any single character.
        mockMvc.perform(as(get("/api/profiles").param("search", "count_1"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    /** A body with the right JSON but the wrong shape names the field instead of a Java type. */
    @Test
    void namesTheFieldWhenTheBodyHasTheWrongShape() throws Exception {
        mockMvc.perform(as(post("/api/profiles"), tokenFor("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Shape","tags":"not-a-list","template":{"selection":{"scenario":"checkout"}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"))
                .andExpect(jsonPath("$.message").value("'tags' has the wrong type"));

        mockMvc.perform(as(post("/api/profiles"), tokenFor("alice")).contentType(MediaType.TEXT_PLAIN)
                        .content("name=Shape"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    /** A body over the request limit is refused on its length alone, before it is parsed at all. */
    @Test
    void refusesABodyThatIsTooBigToEvenRead() throws Exception {
        mockMvc.perform(as(post("/api/profiles"), tokenFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(31_000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Payload too large"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("30,000 byte limit")));
    }

    /** The request id a client sends is echoed back — cleaned, cut, and never the cause of a failure. */
    @Test
    void echoesASanitisedRequestId() throws Exception {
        mockMvc.perform(as(get("/api/profiles"), tokenFor("bob")).header("X-Request-Id", "trace.42/abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "trace42abc"));

        mockMvc.perform(as(get("/api/profiles"), tokenFor("bob")).header("X-Request-Id", "!!!"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesRegex("[0-9a-f-]{36}")));
    }

    /** Nothing outside /api slips through without a token, except the API's own description. */
    @Test
    void coversEveryPathWithTheSameRule() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/somewhere/else")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/somewhere/else"), tokenFor("bob"))).andExpect(status().isNotFound());
    }

    /**
     * A shared store needs to say who changed a scenario. The name comes from the token, so a
     * client cannot claim to be someone else, and a row written before the columns existed shows
     * no author rather than a guessed one.
     */
    @Test
    void recordsWhoWroteEachProfile() throws Exception {
        String alice = tokenFor("alice");
        String id = create(alice, checkout("Authored", "ORD-AUTHOR-1"));

        mockMvc.perform(as(get("/api/profiles/{id}", id), alice))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.updatedBy").value("alice"));

        // bob edits it: the author stays alice, the last hand becomes bob.
        mockMvc.perform(as(put("/api/profiles/{id}", id), tokenFor("bob")).header(HttpHeaders.IF_MATCH, etagOf(alice, id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkout("Authored", "ORD-AUTHOR-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.updatedBy").value("bob"));

        // The list carries the last hand too, so the table can show it without opening each row.
        mockMvc.perform(as(get("/api/profiles").param("search", "Authored"), alice))
                .andExpect(jsonPath("$.items[0].updatedBy").value("bob"));

        // Claiming to be someone else in the body changes nothing: the name is read from the token.
        mockMvc.perform(as(put("/api/profiles/{id}", id), tokenFor("bob")).header(HttpHeaders.IF_MATCH, etagOf(alice, id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Authored","updatedBy":"alice","createdBy":"alice"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy").value("bob"));
    }
}
