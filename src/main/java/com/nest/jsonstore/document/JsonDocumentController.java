package com.nest.jsonstore.document;

import com.nest.jsonstore.document.dto.JsonDocumentRequest;
import com.nest.jsonstore.document.dto.JsonDocumentResponse;
import com.nest.jsonstore.document.dto.JsonDocumentSummary;
import com.nest.jsonstore.document.dto.PageResponse;
import com.nest.jsonstore.document.dto.StoreStats;
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
@RequestMapping("/api/documents")
class JsonDocumentController {

    private final JsonDocumentService service;

    JsonDocumentController(JsonDocumentService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<JsonDocumentSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.list(search, page, size, sort, direction);
    }

    @GetMapping("/stats")
    StoreStats stats() {
        return service.stats();
    }

    @GetMapping("/{id}")
    JsonDocumentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<JsonDocumentResponse> create(@Valid @RequestBody JsonDocumentRequest request) {
        JsonDocumentResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/documents/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    JsonDocumentResponse update(@PathVariable UUID id, @Valid @RequestBody JsonDocumentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
