package com.modelcollab.diagram.repository;

import com.modelcollab.diagram.model.DiagramOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiagramOperationRepository extends JpaRepository<DiagramOperation, UUID> {

    /**
     * UC19 -- Eliminar Proyecto (borrado en cascada manual). No existe
     * {@code ON DELETE CASCADE} en base de datos ni relacion JPA entre
     * {@code Diagram} y {@code DiagramOperation} (ambas entidades usan columnas
     * UUID sueltas, no {@code @OneToMany}), asi que {@code ProjectService}
     * necesita borrar explicitamente las operaciones de un diagrama antes de
     * poder borrar el diagrama en si.
     *
     * @return cuantas filas se borraron
     */
    long deleteByDiagramId(UUID diagramId);
}
