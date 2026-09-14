package com.nest.jsonstore.profile;

import com.nest.jsonstore.profile.dto.RecomposeOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Maintenance that only administrators may run. The filter chain restricts /api/admin to them. */
@RestController
@RequestMapping("/api/admin/profiles")
@Tag(name = "Administration")
class ProfileAdminController {

    private final ProfileService service;

    ProfileAdminController(ProfileService service) {
        this.service = service;
    }

    /**
     * Rebuilds every templated profile from its own stored template against the current catalogue.
     * A dry run unless {@code apply=true}, so the report can be read before anything is written.
     */
    @Operation(summary = "Rebuild stored inputs from their templates; a dry run unless apply=true")
    @PostMapping("/recompose")
    List<RecomposeOutcome> recompose(@RequestParam(defaultValue = "false") boolean apply) {
        return service.recomposeAll(apply);
    }
}
