package com.modelcollab.metamodel.model;

/**
 * Tipos de relacion estructural entre dos clases del modelo canonico.
 *
 * <p>Los primeros 6 valores son exactamente los tipos de relacion de diagrama de
 * clases del estandar OMG UML 2.5.1 (diciembre 2017): ASSOCIATION, AGGREGATION,
 * COMPOSITION, GENERALIZATION, DEPENDENCY, REALIZATION. {@code MANY_TO_MANY} NO es
 * un tipo de relacion UML (una asociacion muchos-a-muchos se modela en UML como una
 * simple ASSOCIATION con multiplicidad {@code 0..*} en ambos extremos); se conserva
 * como un septimo valor puramente de infraestructura de persistencia/ORM, usado
 * solo por {@code MetamodelValidator} para exigir {@code owningSide}/{@code
 * joinTableName} de cara a la generacion de tabla intermedia JPA, y nunca se expone
 * como opcion de notacion UML en el frontend (ver ViewModeToggle / UmlRelationshipEdge).</p>
 */
public enum RelationshipType {
    ASSOCIATION,
    AGGREGATION,
    COMPOSITION,
    GENERALIZATION,
    DEPENDENCY,
    REALIZATION,
    MANY_TO_MANY
}
