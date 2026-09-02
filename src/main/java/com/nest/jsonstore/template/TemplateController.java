package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/templates")
class TemplateController {

    private final TemplateCatalog catalog;

    TemplateController(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    /** The fragments and their fields; the browser composes and previews the result locally. */
    @GetMapping
    JsonNode catalog() {
        return catalog.asJson();
    }
}
