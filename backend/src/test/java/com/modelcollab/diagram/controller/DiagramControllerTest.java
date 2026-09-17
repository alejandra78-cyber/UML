package com.modelcollab.diagram.controller;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.auth.service.JwtService;
import com.modelcollab.diagram.model.Diagram;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cubre los dos endpoints de la seccion 14 implementados en {@link DiagramController},
 * incluyendo la regla de rol reutilizada de {@code StompChannelInterceptor}
 * (OWNER/EDITOR pueden crear, cualquier miembro puede leer el snapshot).
 *
 * <p>{@code @Transactional} en la clase hace que cada test corra en su propia
 * transaccion, revertida al final: los datos de prueba no ensucian la base de
 * datos real de desarrollo contra la que corre esta suite.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DiagramControllerTest {

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
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createDiagram_asOwner_returnsCreatedWithEmptyCanonicalModel() throws Exception {
        User owner = createUser("owner-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(post("/api/v1/projects/{projectId}/diagrams", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mi Diagrama\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.name").value("Mi Diagrama"))
                .andExpect(jsonPath("$.schemaVersion").value("1.0.0"))
                .andExpect(jsonPath("$.mutationVersion").value(1));
    }

    @Test
    void createDiagram_asEditor_returnsCreated() throws Exception {
        User editor = createUser("editor-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(editor, ProjectRole.EDITOR);

        mockMvc.perform(post("/api/v1/projects/{projectId}/diagrams", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Diagrama de editor\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createDiagram_asViewer_isForbidden() throws Exception {
        User viewer = createUser("viewer-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(viewer, ProjectRole.VIEWER);

        mockMvc.perform(post("/api/v1/projects/{projectId}/diagrams", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No deberia crearse\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDiagram_nonMemberOfProject_isForbidden() throws Exception {
        User owner = createUser("owner2-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User outsider = createUser("outsider-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/projects/{projectId}/diagrams", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No soy miembro\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDiagram_blankName_isBadRequest() throws Exception {
        User owner = createUser("owner3-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(post("/api/v1/projects/{projectId}/diagrams", project.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSnapshot_asViewer_returnsCurrentCanonicalState() throws Exception {
        User owner = createUser("owner4-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        User viewer = createUser("viewer2-" + UUID.randomUUID() + "@example.com");
        projectMemberRepository.save(new ProjectMember(project.getId(), viewer.getId(), ProjectRole.VIEWER));

        String initialState = "{\"schemaVersion\":\"1.0.0\",\"mutationVersion\":1,\"packages\":[],"
                + "\"classes\":[],\"relationships\":[]}";
        Diagram diagram = diagramRepository.save(new Diagram(project.getId(), "Diagrama", "1.0.0", 1, initialState));

        mockMvc.perform(get("/api/v1/diagrams/{diagramId}/snapshot", diagram.getId())
                        .header("Authorization", "Bearer " + tokenFor(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(diagram.getId().toString()))
                .andExpect(jsonPath("$.currentState").value(initialState));
    }

    @Test
    void getSnapshot_nonMemberOfProject_isForbidden() throws Exception {
        User owner = createUser("owner5-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);
        Diagram diagram = diagramRepository.save(new Diagram(project.getId(), "Diagrama",
                "1.0.0", 1, "{\"schemaVersion\":\"1.0.0\",\"mutationVersion\":1,\"packages\":[],\"classes\":[],\"relationships\":[]}"));
        User outsider = createUser("outsider2-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(get("/api/v1/diagrams/{diagramId}/snapshot", diagram.getId())
                        .header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSnapshot_nonExistentDiagram_isNotFound() throws Exception {
        User owner = createUser("owner6-" + UUID.randomUUID() + "@example.com");
        createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(get("/api/v1/diagrams/{diagramId}/snapshot", UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createDiagram_withoutToken_isRejected() throws Exception {
        User owner = createUser("owner7-" + UUID.randomUUID() + "@example.com");
        Project project = createProjectWithMember(owner, ProjectRole.OWNER);

        mockMvc.perform(post("/api/v1/projects/{projectId}/diagrams", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sin token\"}"))
                .andExpect(status().is4xxClientError());
    }

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
