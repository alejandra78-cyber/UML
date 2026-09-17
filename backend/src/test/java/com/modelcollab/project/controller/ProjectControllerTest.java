package com.modelcollab.project.controller;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.auth.service.JwtService;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.model.DiagramOperation;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import com.modelcollab.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@link ProjectController}: creation, listing accessible projects,
 * and the two endpoints added for UC18 (invitar colaborador y asignar rol) and
 * UC19 (eliminar proyecto), including the insufficient-role (403) and
 * unauthenticated cases.
 *
 * <p>Same style as {@code DiagramControllerTest}: {@code @SpringBootTest} +
 * {@code @AutoConfigureMockMvc} + {@code @Transactional} (each test rolls
 * back), real users/projects persisted via the repositories, a real JWT from
 * {@code JwtService}, and real HTTP requests through {@code MockMvc}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private DiagramRepository diagramRepository;

    @Autowired
    private DiagramOperationRepository diagramOperationRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---- creation ----------------------------------------------------

    @Test
    void createProject_makesCreatorOwner() throws Exception {
        User owner = createUser("owner-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Proyecto Nuevo\",\"description\":\"desc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Proyecto Nuevo"))
                .andExpect(jsonPath("$.ownerId").value(owner.getId().toString()));

        assertThat(projectMemberRepository.findByUserId(owner.getId())).hasSize(1);
        assertThat(projectMemberRepository.findByUserId(owner.getId()).get(0).getRole()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    void createProject_withoutToken_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sin token\"}"))
                .andExpect(status().is4xxClientError());
    }

    // ---- listing -------------------------------------------------------

    @Test
    void listProjects_returnsOwnedAndMemberProjects() throws Exception {
        User user = createUser("lister-" + UUID.randomUUID() + "@example.com");
        Project owned = createProjectWithMember(user, ProjectRole.OWNER);

        User otherOwner = createUser("otherowner-" + UUID.randomUUID() + "@example.com");
        Project memberOf = projectRepository.save(new Project("Proyecto ajeno", "d", otherOwner.getId()));
        projectMemberRepository.save(new ProjectMember(memberOf.getId(), otherOwner.getId(), ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(memberOf.getId(), user.getId(), ProjectRole.VIEWER));

        Project notAccessible = projectRepository.save(new Project("No deberia verse", "d", otherOwner.getId()));
        projectMemberRepository.save(new ProjectMember(notAccessible.getId(), otherOwner.getId(), ProjectRole.OWNER));

        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + owned.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + memberOf.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + notAccessible.getId() + "')]").doesNotExist());
    }

    // ---- UC18: invitar colaborador / asignar rol ------------------------

    @Test
    void addMember_asOwner_invitingNewUser_returnsCreatedAndPersistsRole() throws Exception {
        User owner = createUser("owner2-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User invitee = createUser("invitee-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitee.getEmail() + "\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated());

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(project.getId(), invitee.getId())
                .orElseThrow();
        assertThat(member.getRole()).isEqualTo(ProjectRole.EDITOR);
    }

    @Test
    void addMember_asOwner_reInvitingExistingMember_updatesRoleAndReturnsOk() throws Exception {
        User owner = createUser("owner3-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User existingMember = createUser("existing-" + UUID.randomUUID() + "@example.com");
        projectMemberRepository.save(new ProjectMember(project.getId(), existingMember.getId(), ProjectRole.VIEWER));

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + existingMember.getEmail() + "\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk());

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(project.getId(), existingMember.getId())
                .orElseThrow();
        assertThat(member.getRole()).isEqualTo(ProjectRole.EDITOR);
        assertThat(projectMemberRepository.findByProjectId(project.getId())).hasSize(2);
    }

    @Test
    void addMember_asEditor_isForbidden() throws Exception {
        User owner = createUser("owner4-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User editor = createUser("editor-" + UUID.randomUUID() + "@example.com");
        projectMemberRepository.save(new ProjectMember(project.getId(), editor.getId(), ProjectRole.EDITOR));
        User invitee = createUser("invitee2-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitee.getEmail() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void addMember_nonMemberOfProject_isForbidden() throws Exception {
        User owner = createUser("owner5-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User outsider = createUser("outsider-" + UUID.randomUUID() + "@example.com");
        User invitee = createUser("invitee3-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitee.getEmail() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void addMember_emailNotRegistered_isNotFound() throws Exception {
        User owner = createUser("owner6-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-existe-" + UUID.randomUUID() + "@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMember_projectDoesNotExist_isNotFound() throws Exception {
        User owner = createUser("owner7-" + UUID.randomUUID() + "@example.com");
        User invitee = createUser("invitee4-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitee.getEmail() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMember_withoutToken_isRejected() throws Exception {
        User owner = createUser("owner8-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(post("/api/v1/projects/{projectId}/members", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().is4xxClientError());
    }

    // ---- UC19: eliminar proyecto -----------------------------------------

    @Test
    void deleteProject_asOwner_cascadesToMembersAndDiagrams() throws Exception {
        User owner = createUser("owner9-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User viewer = createUser("viewer3-" + UUID.randomUUID() + "@example.com");
        projectMemberRepository.save(new ProjectMember(project.getId(), viewer.getId(), ProjectRole.VIEWER));

        Diagram diagram = diagramRepository.save(new Diagram(project.getId(), "Diagrama", "1.0.0", 1,
                "{\"schemaVersion\":\"1.0.0\",\"mutationVersion\":1,\"packages\":[],\"classes\":[],\"relationships\":[]}"));
        DiagramOperation operation = diagramOperationRepository.save(
                new DiagramOperation(diagram.getId(), owner.getId(), "ADD_CLASS", "{}", "client-1", 1L));

        mockMvc.perform(delete("/api/v1/projects/{projectId}", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isNoContent());

        assertThat(projectRepository.findById(project.getId())).isEmpty();
        assertThat(projectMemberRepository.findByProjectId(project.getId())).isEmpty();
        assertThat(diagramRepository.findById(diagram.getId())).isEmpty();
        assertThat(diagramOperationRepository.findById(operation.getId())).isEmpty();
    }

    @Test
    void deleteProject_asViewer_isForbidden() throws Exception {
        User owner = createUser("owner10-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User viewer = createUser("viewer4-" + UUID.randomUUID() + "@example.com");
        projectMemberRepository.save(new ProjectMember(project.getId(), viewer.getId(), ProjectRole.VIEWER));

        mockMvc.perform(delete("/api/v1/projects/{projectId}", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(viewer)))
                .andExpect(status().isForbidden());

        assertThat(projectRepository.findById(project.getId())).isPresent();
    }

    @Test
    void deleteProject_nonExistentProject_isNotFound() throws Exception {
        User owner = createUser("owner11-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(delete("/api/v1/projects/{projectId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProject_withoutToken_isRejected() throws Exception {
        User owner = createUser("owner12-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(delete("/api/v1/projects/{projectId}", project.getId()))
                .andExpect(status().is4xxClientError());
    }

    // ---- helpers ---------------------------------------------------------

    private User createUser(String email) {
        return userRepository.save(new User(email, passwordEncoder.encode("password123"), "Usuario de prueba"));
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getEmail());
    }

    private Project createProjectWithMember(User member, ProjectRole role) {
        Project project = projectRepository.save(new Project("Proyecto de prueba", "desc", member.getId()));
        projectMemberRepository.save(new ProjectMember(project.getId(), member.getId(), role));
        return project;
    }
}
