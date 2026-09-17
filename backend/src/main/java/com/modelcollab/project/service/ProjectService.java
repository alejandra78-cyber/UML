package com.modelcollab.project.service;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import com.modelcollab.project.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final DiagramRepository diagramRepository;
    private final DiagramOperationRepository diagramOperationRepository;
    private final UserRepository userRepository;

    public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
                           DiagramRepository diagramRepository, DiagramOperationRepository diagramOperationRepository,
                           UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.diagramRepository = diagramRepository;
        this.diagramOperationRepository = diagramOperationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a project and automatically makes the creator the {@code OWNER}
     * in {@code project_members}.
     */
    @Transactional
    public Project createProject(UUID ownerId, String name, String description) {
        Project project = projectRepository.save(new Project(name, description, ownerId));
        projectMemberRepository.save(new ProjectMember(project.getId(), ownerId, ProjectRole.OWNER));
        return project;
    }

    /**
     * Lists every project the given user can access: projects they own, plus
     * projects where they appear in {@code project_members} (any role).
     * Combines two simple derived queries in the service layer instead of a
     * single derived query navigating the {@code Project -> ProjectMember}
     * association, for portability across Spring Data JPA versions.
     */
    @Transactional(readOnly = true)
    public List<Project> listAccessibleProjects(UUID userId) {
        Map<UUID, Project> byId = new LinkedHashMap<>();

        for (Project owned : projectRepository.findByOwnerId(userId)) {
            byId.put(owned.getId(), owned);
        }

        List<UUID> memberProjectIds = projectMemberRepository.findByUserId(userId).stream()
                .map(ProjectMember::getProjectId)
                .toList();

        if (!memberProjectIds.isEmpty()) {
            for (Project project : projectRepository.findAllById(memberProjectIds)) {
                byId.putIfAbsent(project.getId(), project);
            }
        }

        return List.copyOf(byId.values());
    }

    /**
     * UC18 -- Invitar Colaborador y Asignar Rol. Only the project {@code OWNER}
     * may invite/re-role members. The invitee is identified by {@code email}
     * (resolved to a {@code UUID} via {@link UserRepository#findByEmail}) since
     * that is what the inviter actually knows about the person they want to add.
     *
     * <p>Looks up {@code (projectId, userId)} in {@code project_members} first
     * (rather than relying on the composite-PK duplicate-key exception the
     * database would otherwise raise) so the alta-vs-update decision is
     * explicit: if the user is already a member, their {@code role} is updated
     * in place instead of inserting a second row -- the composite PK
     * {@code (project_id, user_id)} would reject a duplicate insert anyway, but
     * this way the behavior doesn't depend on catching that exception.</p>
     *
     * @throws ResponseStatusException 404 if {@code projectId} does not exist,
     *                                  or if no user is registered with
     *                                  {@code email}; 403 if
     *                                  {@code requestingUserId} is not the
     *                                  project's {@code OWNER}
     */
    @Transactional
    public MemberUpsertResult addOrUpdateMember(UUID projectId, UUID requestingUserId, String email, ProjectRole role) {
        requireProjectExists(projectId);
        requireOwner(projectId, requestingUserId);

        User invitedUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No existe ningun usuario registrado con el email " + email));

        Optional<ProjectMember> existing = projectMemberRepository.findByProjectIdAndUserId(projectId, invitedUser.getId());
        if (existing.isPresent()) {
            ProjectMember member = existing.get();
            member.setRole(role);
            return new MemberUpsertResult(projectMemberRepository.save(member), false);
        }

        ProjectMember created = projectMemberRepository.save(new ProjectMember(projectId, invitedUser.getId(), role));
        return new MemberUpsertResult(created, true);
    }

    /**
     * UC19 -- Eliminar Proyecto. Only the project {@code OWNER} may delete it.
     *
     * <p><b>No hay cascada de base de datos.</b> No existe ningun
     * {@code schema.sql} en este proyecto -- Hibernate gestiona el esquema con
     * {@code spring.jpa.hibernate.ddl-auto=update} -- y {@code Project},
     * {@code ProjectMember}, {@code Diagram} y {@code DiagramOperation} se
     * modelan con columnas UUID sueltas ({@code ownerId}/{@code projectId}/
     * {@code diagramId}), no con relaciones JPA ({@code @OneToMany}/
     * {@code @ManyToOne}). Por lo tanto no hay ningun {@code ON DELETE CASCADE}
     * automatico -- ni de JPA ni de la base -- y este metodo borra los hijos a
     * mano, en orden: por cada diagrama del proyecto, sus
     * {@code DiagramOperation} y luego el {@code Diagram}; despues todos los
     * {@code ProjectMember}; finalmente el {@code Project}.</p>
     *
     * @throws ResponseStatusException 404 if the project does not exist;
     *                                  403 if {@code requestingUserId} is not
     *                                  the project's {@code OWNER}
     */
    @Transactional
    public void deleteProject(UUID projectId, UUID requestingUserId) {
        requireProjectExists(projectId);
        requireOwner(projectId, requestingUserId);

        for (Diagram diagram : diagramRepository.findByProjectIdOrderByIdAsc(projectId)) {
            diagramOperationRepository.deleteByDiagramId(diagram.getId());
            diagramRepository.delete(diagram);
        }

        projectMemberRepository.deleteByProjectId(projectId);
        projectRepository.deleteById(projectId);
    }

    private void requireProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId);
        }
    }

    /**
     * Confirms {@code userId} holds role {@code OWNER} in {@code projectId},
     * or rejects with 403. Callers that also need to distinguish "project
     * doesn't exist" (404) must call {@link #requireProjectExists} first --
     * this method alone cannot tell that case apart from "not a member",
     * since both look identical in {@code project_members}.
     */
    private void requireOwner(UUID projectId, UUID userId) {
        ProjectRole role = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(ProjectMember::getRole)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
        if (role != ProjectRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el OWNER del proyecto puede realizar esta accion");
        }
    }

    /**
     * @param created {@code true} if a new {@code project_members} row was
     *                inserted; {@code false} if an existing member's role was
     *                updated instead. Lets the controller answer with
     *                {@code 201 Created} vs {@code 200 OK} accordingly.
     */
    public record MemberUpsertResult(ProjectMember member, boolean created) {
    }
}
