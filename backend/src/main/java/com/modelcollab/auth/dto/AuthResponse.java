package com.modelcollab.auth.dto;

import java.util.UUID;

/**
 * Response returned by {@code /api/v1/auth/register} and {@code /api/v1/auth/login}.
 */
public record AuthResponse(
        String token,
        UUID userId,
        String email,
        String fullName
) {
}
