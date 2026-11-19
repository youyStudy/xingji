package com.xingji.controller;


import com.xingji.dto.LoginDTO;
import com.xingji.dto.RegisterDTO;
import com.xingji.service.UserService;
import com.xingji.common.Result;
import com.xingji.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Data
@RestController
@RequestMapping("/api/auth")
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO registerDTO){
        userService.register(registerDTO.getPhone(),registerDTO.getEmail(),
                registerDTO.getPassword(),registerDTO.getNickname());
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO){
        String token = userService.login(loginDTO.getPhone(), loginDTO.getPassword());
        return Result.ok(new LoginVO(token));
    }


}
