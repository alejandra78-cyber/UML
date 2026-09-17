package com.modelcollab.project.dto;

import com.modelcollab.project.model.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code POST /api/v1/projects/{projectId}/members} (UC18 -
 * Invitar Colaborador y Asignar Rol).
 *
 * <p>The user to invite is identified by {@code email} rather than by
 * {@code UUID}: the inviter (a project {@code OWNER}) knows the collaborator's
 * email, not their internal id. {@code ProjectService} resolves the email to
 * a {@code UUID} via {@code UserRepository.findByEmail}, rejecting with 404
 * if no such user is registered.</p>
 *
 * <p>{@code role} is typed as {@link ProjectRole} directly (not {@code String})
 * so that an invalid literal fails Jackson deserialization with a 400 before
 * ever reaching {@code ProjectService}, the same way {@code role} is handled
 * server-side via {@code @Enumerated(EnumType.STRING)} on
 * {@link com.modelcollab.project.model.ProjectMember}.</p>
 */
public record InviteMemberRequest(

        @NotBlank(message = "email is required")
        @Email(message = "email must be a well-formed email address")
        String email,

        @NotNull(message = "role is required")
        ProjectRole role
) {
}
