package com.modelcollab.diagram.repository;

import com.modelcollab.diagram.model.Diagram;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiagramRepository extends JpaRepository<Diagram, UUID> {

    @Query("select d.projectId from Diagram d where d.id = :id")
    Optional<UUID> findProjectIdById(@Param("id") UUID id);

    // orderBy explícito: sin él, dos llamadas de clientes distintos (p.ej. dos
    // usuarios resolviendo su diagrama activo en ensureActiveDiagram) no tienen
    // garantía de ver la lista en el mismo orden, y "diagrams[0]" podría diferir
    // entre ellos si el proyecto llegara a tener más de un diagrama.
    List<Diagram> findByProjectIdOrderByIdAsc(UUID projectId);

    /**
     * Igual que {@code findById}, pero toma un lock pesimista de escritura
     * ({@code SELECT ... FOR UPDATE}) sobre la fila del diagrama.
     *
     * <p>{@code DiagramMutationService.apply} hace un ciclo clásico de
     * lectura-modificación-escritura sobre {@code current_state} (lee el JSON
     * completo, aplica una operación en memoria, escribe el JSON completo de
     * vuelta). Sin este lock, dos mutaciones concurrentes sobre el MISMO
     * diagrama (p.ej. dos {@code ADD_CLASS} seguidos, o un {@code MOVE_CLASS}
     * justo después de un {@code ADD_CLASS}, procesados en threads distintos
     * del pool de {@code inboundChannel} de STOMP) pueden leer el mismo estado
     * "de antes" y la que guarda último pisa por completo el trabajo de la
     * otra (lost update): una clase recién agregada desaparece sin dejar
     * rastro. Este lock serializa esas escrituras por diagrama: la segunda
     * transacción espera a que la primera confirme antes de leer, así siempre
     * parte del estado más reciente.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Diagram d where d.id = :id")
    Optional<Diagram> findByIdForUpdate(@Param("id") UUID id);
}
