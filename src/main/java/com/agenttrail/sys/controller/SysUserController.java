package com.agenttrail.sys.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.dto.SysUserPage;
import com.agenttrail.sys.dto.UserCreateRequest;
import com.agenttrail.sys.dto.UserPageVO;
import com.agenttrail.sys.dto.UserStatusRequest;
import com.agenttrail.sys.dto.UserUpdateRequest;
import com.agenttrail.sys.dto.UserVO;
import com.agenttrail.sys.entity.SysUser;
import com.agenttrail.sys.service.SysUserService;
import com.agenttrail.sys.store.JdbcUserStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端用户 CRUD；具体校验放在 Service，权限码写在方法上做到接口意图自解释。 */
@RestController
public class SysUserController {
    private final SysUserService userService;
    private final JdbcUserStore userStore;

    public SysUserController(SysUserService userService, JdbcUserStore userStore) {
        this.userService = userService; this.userStore = userStore;
    }

    @GetMapping("/api/sys/users")
    @SaCheckPermission("sys:user:view")
    public UserPageVO list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        SysUserPage result = userService.search(keyword, page, size);
        return new UserPageVO(result.items().stream().map(this::toVO).toList(), result.total(), result.page(), result.size());
    }

    @PostMapping("/api/sys/users")
    @SaCheckPermission("sys:user:create")
    public UserVO create(@RequestBody UserCreateRequest request) { return toVO(userService.create(request)); }

    @PutMapping("/api/sys/users/{id}")
    @SaCheckPermission("sys:user:update")
    public UserVO update(@PathVariable long id, @RequestBody UserUpdateRequest request) { return toVO(userService.update(id, request)); }

    @PatchMapping("/api/sys/users/{id}/status")
    @SaCheckPermission("sys:user:manage-status")
    public void updateStatus(@PathVariable long id, @RequestBody UserStatusRequest request) {
        userService.updateStatus(id, request.status());
    }

    @DeleteMapping("/api/sys/users/{id}")
    @SaCheckPermission("sys:user:delete")
    public void delete(@PathVariable long id) { userService.delete(id); }

    private UserVO toVO(SysUser user) {
        return UserVO.of(user, userStore.findRolesByUserId(user.id()), userStore.findDeptIdsByUserId(user.id()));
    }
}
