package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RolePermission - a hospital's OT permission grant to one role.
 *
 * Binds to the role STRING rather than a designation entity: this HMS has no
 * designation concept (User.role is a plain String), so introducing one would be a
 * separate project. A "new designation" is therefore a new role string plus its rows
 * here; promoting role to a first-class designation later is a rename, because nothing
 * else references it.
 *
 * Overrides only. A hospital with NO rows uses OtPermissions.defaultsFor(role); the
 * first save materialises the whole matrix, after which these rows are the sole truth
 * (otherwise "revoke everything from a role" would be indistinguishable from "unset").
 */
@Entity
@Table(name = "role_permissions",
        uniqueConstraints = @UniqueConstraint(name = "UK_role_permission",
                columnNames = {"hospital_id", "role", "permission_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "permission_code", nullable = false, length = 40)
    private String permissionCode;
}
