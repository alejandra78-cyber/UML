package ${basePackage}.controller;

import ${basePackage}.service.${cls.serviceName};
import ${basePackage}.service.dto.${cls.requestDtoName};
import ${basePackage}.service.dto.${cls.responseDtoName};
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints CRUD estándar para '${cls.className}'. Sin OpenAPI/Swagger (omitido
 * deliberadamente por alcance, ver reporte del generador UC13).
 */
@RestController
@RequestMapping("/api/${cls.resourcePathPlural}")
public class ${cls.controllerName} {

    private final ${cls.serviceName} service;

    public ${cls.controllerName}(${cls.serviceName} service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<${cls.responseDtoName}> create(@Valid @RequestBody ${cls.requestDtoName} request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    public List<${cls.responseDtoName}> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ${cls.responseDtoName} findById(@PathVariable ${cls.effectivePrimaryKeyType} id) {
        return service.findById(id);
    }

    @PutMapping("/{id}")
    public ${cls.responseDtoName} update(@PathVariable ${cls.effectivePrimaryKeyType} id,
                                          @Valid @RequestBody ${cls.requestDtoName} request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable ${cls.effectivePrimaryKeyType} id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
