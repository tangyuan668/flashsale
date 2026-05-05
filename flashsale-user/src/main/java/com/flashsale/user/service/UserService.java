package com.flashsale.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.ErrorCode;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.util.JwtUtil;
import com.flashsale.user.dto.UserLoginRequest;
import com.flashsale.user.dto.UserRegisterRequest;
import com.flashsale.user.entity.User;
import com.flashsale.user.mapper.UserMapper;
import com.flashsale.user.vo.UserLoginResponse;
import com.flashsale.user.vo.UserInfoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务
 */
@Slf4j
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String USER_TOKEN_PREFIX = "user:token:";
    private static final Duration TOKEN_EXPIRE = Duration.ofDays(7);

    /**
     * 用户注册
     */
    public void register(UserRegisterRequest request) {
        // 检查手机号是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, request.getPhone());
        Long count = userMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // 创建用户
        User user = new User();
        user.setPhone(request.getPhone());
        // BCrypt加密密码（实际使用应该用BCrypt，这里简化处理）
        user.setPassword(encryptPassword(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : "用户" + request.getPhone().substring(7));
        user.setStatus(1);

        int result = userMapper.insert(user);
        if (result <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
        }

        log.info("用户注册成功: phone={}", request.getPhone());
    }

    /**
     * 用户登录
     */
    public UserLoginResponse login(UserLoginRequest request) {
        // 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, request.getPhone());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 验证密码
        if (!verifyPassword(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        // 检查用户状态
        if (user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "账号已被禁用");
        }

        // 生成Token
        String token = jwtUtil.generateToken(user.getId(), user.getPhone());

        // 缓存Token到Redis
        String cacheKey = USER_TOKEN_PREFIX + user.getId();
        redisTemplate.opsForValue().set(cacheKey, token, TOKEN_EXPIRE);

        log.info("用户登录成功: phone={}", request.getPhone());

        return new UserLoginResponse(token, user.getId(), user.getPhone(), user.getNickname(), user.getAvatar());
    }

    /**
     * 获取用户信息
     */
    public UserInfoResponse getUserInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        return new UserInfoResponse(
                user.getId(),
                user.getPhone(),
                user.getNickname(),
                user.getAvatar(),
                user.getStatus(),
                user.getCreateTime()
        );
    }

    /**
     * 登出
     */
    public void logout(Long userId) {
        String cacheKey = USER_TOKEN_PREFIX + userId;
        redisTemplate.delete(cacheKey);
        log.info("用户登出: userId={}", userId);
    }

    /**
     * 验证Token
     */
    public boolean validateToken(String token, Long userId) {
        // 验证JWT有效性
        if (!jwtUtil.validateToken(token)) {
            return false;
        }

        // 验证Redis中的Token是否一致
        String cacheKey = USER_TOKEN_PREFIX + userId;
        String cachedToken = (String) redisTemplate.opsForValue().get(cacheKey);
        return token.equals(cachedToken);
    }

    /**
     * 加密密码（简化版，实际应用应使用BCrypt）
     */
    private String encryptPassword(String password) {
        // 简单加密，生产环境应使用 BCryptPasswordEncoder
        return "$2a$10$" + password; // 占位，实际应使用BCrypt
    }

    /**
     * 验证密码（简化版，实际应用应使用BCrypt）
     */
    private boolean verifyPassword(String rawPassword, String encodedPassword) {
        // 简化验证，生产环境应使用 BCryptPasswordEncoder
        if (encodedPassword.startsWith("$2a$10$")) {
            return encodedPassword.endsWith(rawPassword);
        }
        return encodedPassword.equals(rawPassword);
    }
}
