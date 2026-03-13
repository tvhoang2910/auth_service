package com.exam_bank.auth_service.dto.request;

import com.exam_bank.auth_service.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @Email
    @NotBlank
    @Size(max = 255)
    private String email;

    @Size(min = 8, max = 72)
    private String password;

    @NotBlank
    @Size(max = 150)
    private String fullName;

    private Role role;
}
