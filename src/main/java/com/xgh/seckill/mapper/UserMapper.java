package com.xgh.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgh.seckill.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 *
 * @author 向国豪
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
