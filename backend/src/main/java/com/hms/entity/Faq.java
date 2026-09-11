package com.hms.entity;

import com.hms.validation.NoEmoji;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Faq - Entity representing Frequently Asked Questions.
 * 
 * Maps to 'faqs' table. Shared across all hospital admins, managed by System Admins.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "faqs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Faq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Question is required")
    @Size(max = 5000, message = "Question is too long")
    @NoEmoji
    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @NotBlank(message = "Answer is required")
    @Size(max = 5000, message = "Answer is too long")
    @NoEmoji
    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Pattern(regexp = "^(HOSPITAL|CLINIC|PHARMACY)?$", message = "Hospital type must be HOSPITAL, CLINIC or PHARMACY")
    @Column(name = "hospital_type")
    private String hospitalType; // HOSPITAL, CLINIC, or PHARMACY - for tenant isolation

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getHospitalType() {
        return hospitalType;
    }

    public void setHospitalType(String hospitalType) {
        this.hospitalType = hospitalType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
