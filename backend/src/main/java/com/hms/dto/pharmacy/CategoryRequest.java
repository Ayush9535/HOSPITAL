package com.hms.dto.pharmacy;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryRequest {
    @NotBlank(message = "Category name is required")
    @Size(max = 100, message = "Category name is too long")
    @NoEmoji
    private String categoryName;

    @Size(max = 500, message = "Description is too long")
    @NoEmoji
    private String description;

    private Boolean isActive;
}
