package com.flashsale.user.controller;

import com.flashsale.common.Result;
import com.flashsale.user.dto.UserLoginRequest;
import com.flashsale.user.dto.UserRegisterRequest;
import com.flashsale.user.service.UserService;
import com.flashsale.user.vo.UserLoginResponse;
import com.flashsale.user.vo.UserInfoResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 用户注册
     * POST /api/user/register
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody UserRegisterRequest request) {
        userService.register(request);
        return Result.ok("注册成功", null);
    }

    /**
     * 用户登录
     * POST /api/user/login
     */
    @PostMapping("/login")
    public Result<UserLoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        UserLoginResponse response = userService.login(request);
        return Result.ok("登录成功", response);
    }

    /**
     * 获取用户信息
     * GET /api/user/info
     */
    @GetMapping("/info")
    public Result<UserInfoResponse> getUserInfo(@RequestHeader("X-User-Id") Long userId) {
        UserInfoResponse response = userService.getUserInfo(userId);
        return Result.ok(response);
    }

    /**
     * 用户登出
     * POST /api/user/logout
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("X-User-Id") Long userId) {
        userService.logout(userId);
        return Result.ok("登出成功", null);
    }
}
