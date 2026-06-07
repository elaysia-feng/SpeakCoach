// UserController — 已认证用户自身长期状态相关的端点。
// 所有路由都通过 JwtAuthFilter 设置到 SecurityContext 的 principal 解析调用方身份；
// 绝不从请求体或路径中读取 userId。
package com.speakcoach.controller;

import com.speakcoach.dto.AuthDtos;
import com.speakcoach.dto.ErrorBookDtos;
import com.speakcoach.security.AuthPrincipal;
import com.speakcoach.service.ErrorBookService;
import com.speakcoach.service.UserAbilityHistoryService;
import com.speakcoach.service.UserAbilityProfileService;
import com.speakcoach.service.UserPreferencesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAbilityProfileService profileService;
    private final UserPreferencesService preferencesService;
    private final ErrorBookService errorBookService;
    private final UserAbilityHistoryService abilityHistoryService;

    @GetMapping("/me/profile")
    public ResponseEntity<AuthDtos.AbilityProfileResponse> getMyProfile(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long userId = principal != null ? principal.userId() : null;
        return ResponseEntity.ok(profileService.getProfile(userId));
    }

    @PutMapping("/me/profile")
    public ResponseEntity<AuthDtos.AbilityProfileResponse> updateMyProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuthDtos.AbilityProfileUpdateRequest request) {
        Long userId = principal != null ? principal.userId() : null;
        return ResponseEntity.ok(profileService.updateProfile(userId, request));
    }

    /** M1-B: 读取当前用户的 coach_persona + preferred_voice 偏好。 */
    @GetMapping("/me/preferences")
    public ResponseEntity<AuthDtos.UserPreferencesResponse> getMyPreferences(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long userId = principal != null ? principal.userId() : null;
        return ResponseEntity.ok(preferencesService.getPreferences(userId));
    }

    /** M1-B: 部分更新当前用户的偏好。任一字段为 null 则保留原值。 */
    @PutMapping("/me/preferences")
    public ResponseEntity<AuthDtos.UserPreferencesResponse> updateMyPreferences(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuthDtos.UserPreferencesUpdateRequest request) {
        Long userId = principal != null ? principal.userId() : null;
        return ResponseEntity.ok(preferencesService.updatePreferences(userId, request));
    }

    // ============================================================
    // M1-C 错题本端点
    // ============================================================

    /** GET /api/users/me/error-book — 列出当前用户的错题（按 mastery / type / limit 过滤）。 */
    @GetMapping("/me/error-book")
    public ResponseEntity<ErrorBookDtos.ErrorBookListResponse> listMyErrorBook(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) Integer mastery,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        Long userId = principal != null ? principal.userId() : null;
        int safeLimit = (limit == null || limit < 0) ? 20 : limit;
        return ResponseEntity.ok(new ErrorBookDtos.ErrorBookListResponse(
                errorBookService.listForUser(userId, mastery, type, safeLimit)
        ));
    }

    /** GET /api/users/me/error-book/stats — 最近 7 天按 type 聚合的新增数。 */
    @GetMapping("/me/error-book/stats")
    public ResponseEntity<Map<String, Object>> errorBookStats(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long userId = principal != null ? principal.userId() : null;
        return ResponseEntity.ok(Map.of("byType", errorBookService.statsByType(userId)));
    }

    /** POST /api/users/me/error-book/{id}/master — 标记某条错题为已掌握。 */
    @PostMapping("/me/error-book/{id}/master")
    public ResponseEntity<Map<String, Object>> markMyErrorMastered(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id) {
        Long userId = principal != null ? principal.userId() : null;
        boolean ok = errorBookService.markMastered(userId, id);
        return ResponseEntity.ok(Map.of("ok", ok));
    }

    // ============================================================
    // M1-A 长期记忆端点
    // ============================================================

    /**
     * GET /api/users/me/profile/timeline?limit=10
     * 拉取当前用户最近 N 条能力画像快照（按 created_at DESC）。
     * 给 Profile 页 sparkline 用。
     */
    @GetMapping("/me/profile/timeline")
    public ResponseEntity<AuthDtos.AbilityHistoryResponse> getMyProfileTimeline(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {
        Long userId = principal != null ? principal.userId() : null;
        int safeLimit = (limit == null || limit < 1) ? 10 : Math.min(limit, 100);
        var entries = abilityHistoryService.getTimeline(userId, safeLimit);
        return ResponseEntity.ok(new AuthDtos.AbilityHistoryResponse(userId, entries.size(), entries));
    }
}
