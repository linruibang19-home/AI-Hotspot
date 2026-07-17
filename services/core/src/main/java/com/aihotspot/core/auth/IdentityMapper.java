package com.aihotspot.core.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {

    UserAccount findUserByEmail(@Param("email") String email);

    UserAccount findUserById(@Param("id") UUID id);

    List<String> findRoleCodes(@Param("userId") UUID userId);

    List<String> findPermissionCodes(@Param("userId") UUID userId);

    int insertUser(UserAccount user);

    int assignRole(
            @Param("userId") UUID userId,
            @Param("roleCode") String roleCode,
            @Param("assignedBy") UUID assignedBy);

    int updateLastLogin(@Param("userId") UUID userId);

    int insertInvitation(Invitation invitation);

    Invitation findInvitationForUpdate(@Param("codeHash") String codeHash);

    int consumeInvitation(@Param("id") UUID id, @Param("version") long version);

    List<AdminUserView> listUsers(@Param("limit") int limit);

    List<InvitationView> listInvitations(@Param("limit") int limit);

    record UserAccount(
            UUID id,
            String email,
            String displayName,
            String passwordHash,
            String status,
            String locale,
            String timezone,
            long version,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt) {}

    record Invitation(
            UUID id,
            String codeHash,
            UUID createdBy,
            String defaultRole,
            int maxUses,
            int usedCount,
            Instant expiresAt,
            String status,
            long version,
            Instant createdAt) {}

    record AdminUserView(
            UUID id,
            String email,
            String displayName,
            String status,
            String roles,
            Instant createdAt,
            Instant lastLoginAt) {}

    record InvitationView(
            UUID id,
            String defaultRole,
            int maxUses,
            int usedCount,
            Instant expiresAt,
            String status,
            Instant createdAt) {}
}
