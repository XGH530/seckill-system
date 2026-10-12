package com.xgh.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体
 *
 * @author 向国豪
 */
@Data
@TableName("t_user")
public class User implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String phone;

    private String nickname;

    /** 角色:USER-普通用户, ADMIN-管理员(管理台接口据此鉴权) */
    private String role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer deleted;
}
