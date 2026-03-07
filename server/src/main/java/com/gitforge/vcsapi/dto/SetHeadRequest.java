package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetHeadRequest(@NotBlank @Size(max = 255) String branch) {
}
