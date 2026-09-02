package com.nest.jsonstore.profile;

import com.nest.jsonstore.profile.dto.ProfileRequest;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import com.nest.jsonstore.profile.dto.PageResponse;
import com.nest.jsonstore.profile.dto.ProfileStats;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

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
    ProfileResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<ProfileResponse> create(@Valid @RequestBody ProfileRequest request) {
        ProfileResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/profiles/" + created.id())).body(created);
    }

    /** Replaces the whole profile: name, description, tags and inputs. */
    @PutMapping("/{id}")
    ProfileResponse update(@PathVariable UUID id, @Valid @RequestBody ProfileRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
