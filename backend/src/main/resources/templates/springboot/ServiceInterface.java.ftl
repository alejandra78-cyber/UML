package ${basePackage}.service;

import ${basePackage}.service.dto.${cls.requestDtoName};
import ${basePackage}.service.dto.${cls.responseDtoName};

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Servicio con las operaciones CRUD estándar de '${cls.className}' más un método
 * por cada operación de negocio definida en el diagrama UML (invariante 5, sección
 * 13.2 del plan arquitectónico).
 */
public interface ${cls.serviceName} {

    ${cls.responseDtoName} create(${cls.requestDtoName} request);

    ${cls.responseDtoName} findById(${cls.effectivePrimaryKeyType} id);

    List<${cls.responseDtoName}> findAll();

    ${cls.responseDtoName} update(${cls.effectivePrimaryKeyType} id, ${cls.requestDtoName} request);

    void delete(${cls.effectivePrimaryKeyType} id);

<#list cls.methods as m>
    ${m.returnType} ${m.name}(${m.parameterList});

</#list>
}
