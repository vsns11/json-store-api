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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real stack against a real PostgreSQL and the in-process LDAP directory: sign-in,
 * the roles that come from LDAP groups, Flyway migrations, the jsonb mapping, payload-inclusive
 * search and the size limit. Requires a working Docker daemon.
 */
@SpringBootTest(properties = {
        "app.seed-examples=false",
        "app.limits.max-payload-bytes=400",
        "app.limits.max-request-bytes=2000",
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

    @Test
    void storesThePayloadAsRealJsonbAndFindsItByItsContents() throws Exception {
        String alice = tokenFor("alice");

        String id = create(alice, """
                {"name":"Topology","tags":["infra"],
                 "payload":{"main":{"services":[{"name":"api","region":"eu-west"}],"active":true}}}""");
        mockMvc.perform(as(get("/api/profiles/{id}", id), alice))
                .andExpect(jsonPath("$.sizeBytes").value(71));

        // Stored as a jsonb object, so PostgreSQL can read inside it.
        String type = jdbcTemplate.queryForObject(
                "select jsonb_typeof(payload -> 'main') from profile where id = ?::uuid", String.class, id);
        String region = jdbcTemplate.queryForObject(
                "select payload -> 'main' -> 'services' -> 0 ->> 'region' from profile where id = ?::uuid", String.class, id);
        assertThat(type).isEqualTo("object");
        assertThat(region).isEqualTo("eu-west");

        // Search reaches into the payload, not just the name.
        mockMvc.perform(as(get("/api/profiles").param("search", "eu-west"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Topology"));

        // Deleting belongs to the admins group: bob is only a developer.
        mockMvc.perform(as(delete("/api/profiles/{id}", id), tokenFor("bob"))).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/profiles/{id}", id), alice)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/profiles/{id}", id), alice)).andExpect(status().isNotFound());
    }

    /** A composed profile keeps the selection it was built from, so it can be edited as a form later. */
    @Test
    void remembersTheTemplateAProfileWasComposedFrom() throws Exception {
        String alice = tokenFor("alice");

        String id = create(alice, """
                {"name":"Composed","payload":{"main":{"scenario":"checkout"}},
                 "template":{"selection":{"scenario":"checkout","payment":"card-approved"},
                             "values":{"scenarioName":"Composed","quantity":2}}}""");

        mockMvc.perform(as(get("/api/profiles/{id}", id), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.selection.payment").value("card-approved"))
                .andExpect(jsonPath("$.template.values.quantity").value(2));

        // A profile written by hand simply has none.
        mockMvc.perform(as(post("/api/profiles"), alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"By hand","payload":{"main":{"a":1}}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.template").doesNotExist());
    }

    /** Tag filtering is exact, unlike the free-text search which would also match the inputs. */
    @Test
    void narrowsToOneTag() throws Exception {
        String alice = tokenFor("alice");

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Smoke one","tags":["smoke","checkout"],"payload":{"main":{"note":"regression lives here too"}}}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Regression one","tags":["regression"],"payload":{"main":{"note":"x"}}}"""))
                .andExpect(status().isCreated());

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
    void rejectsPayloadsOverTheConfiguredLimit() throws Exception {
        String oversized = "{\"main\":{\"blob\":\"" + "x".repeat(500) + "\"}}";

        mockMvc.perform(as(post("/api/profiles"), tokenFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Too big\",\"payload\":%s}".formatted(oversized)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Payload too large"));
    }

    /** A profile can feed several systems, each with its own document. */
    @Test
    void storesOneDocumentPerSystem() throws Exception {
        String alice = tokenFor("alice");

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Two systems","payload":{
                                   "orders-api":{"order":{"sku":"NEST-01"}},
                                   "payments":{"charge":{"amountMinor":4999}}}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload['orders-api'].order.sku").value("NEST-01"))
                .andExpect(jsonPath("$.payload.payments.charge.amountMinor").value(4999));

        mockMvc.perform(as(get("/api/profiles").param("search", "Two systems"), alice))
                .andExpect(status().isOk())
                // Sorted, because PostgreSQL does not keep the order the keys were written in.
                .andExpect(jsonPath("$.items[0].documents", org.hamcrest.Matchers.contains("orders-api", "payments")));
    }

    /** Inputs that are not a set of named documents are refused with a message that says so. */
    @Test
    void refusesInputsThatAreNotNamedDocuments() throws Exception {
        String alice = tokenFor("alice");

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bare","payload":{}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid inputs"));

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bare","payload":[1,2,3]}"""))
                .andExpect(status().isBadRequest());
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

    /** A template is stored next to the inputs and handed straight back to the form, so its shape is checked. */
    @Test
    void refusesATemplateThatIsNotASelectionPlusValues() throws Exception {
        String alice = tokenFor("alice");

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Odd","payload":{"main":{"a":1}},"template":"checkout"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid template"));

        mockMvc.perform(as(post("/api/profiles"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Odd","payload":{"main":{"a":1}},
                                 "template":{"selection":{"scenario":42},"values":{}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid template"));
    }

    /** A search term is text, not a pattern: "100%" finds the characters, not everything. */
    @Test
    void treatsWildcardCharactersInASearchAsText() throws Exception {
        String alice = tokenFor("alice");
        create(alice, """
                {"name":"Discount 100%","payload":{"main":{"note":"a"}}}""");
        create(alice, """
                {"name":"Discount 10","payload":{"main":{"note":"b"}}}""");

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
                                {"name":"Shape","tags":"not-a-list","payload":{"main":{}}}"""))
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
                        .content("{\"name\":\"" + "x".repeat(3000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Payload too large"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("2,000 byte limit")));
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
}
