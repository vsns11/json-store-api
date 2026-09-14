package com.nest.jsonstore.profile;

import com.nest.jsonstore.profile.dto.ProfileRequest;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import com.nest.jsonstore.profile.dto.PageResponse;
import com.nest.jsonstore.profile.dto.ProfileStats;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Profiles. A single profile carries its version as an ETag, and changing or deleting one requires
 * that ETag back in If-Match: 428 without it, 412 when someone else has saved since.
 */
@RestController
@RequestMapping("/api/profiles")
class ProfileController {

    private final ProfileService service;

    ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<ProfileSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.list(search, tag, page, size, sort, direction);
    }

    @GetMapping("/stats")
    ProfileStats stats() {
        return service.stats();
    }

    @GetMapping("/{id}")
    ResponseEntity<ProfileResponse> get(@PathVariable UUID id) {
        return tagged(ResponseEntity.ok(), service.get(id));
    }

    @PostMapping
    ResponseEntity<ProfileResponse> create(@Valid @RequestBody ProfileRequest request) {
        ProfileResponse created = service.create(request);
        return tagged(ResponseEntity.created(URI.create("/api/profiles/" + created.id())), created);
    }

    /** Replaces name, description and tags; with a template, rebuilds the inputs too. */
    @PutMapping("/{id}")
    ResponseEntity<ProfileResponse> update(@PathVariable UUID id,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @Valid @RequestBody ProfileRequest request) {
        return tagged(ResponseEntity.ok(), service.update(id, Versions.expected(ifMatch), request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        service.delete(id, Versions.expected(ifMatch));
    }

    private static ResponseEntity<ProfileResponse> tagged(ResponseEntity.BodyBuilder builder, ProfileResponse profile) {
        return builder.eTag(Versions.etag(profile.version())).body(profile);
    }
}
