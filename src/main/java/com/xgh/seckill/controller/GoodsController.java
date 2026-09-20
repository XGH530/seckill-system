package com.xgh.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgh.seckill.common.result.Result;
import com.xgh.seckill.entity.Goods;
import com.xgh.seckill.entity.SeckillActivity;
import com.xgh.seckill.mapper.GoodsMapper;
import com.xgh.seckill.mapper.SeckillActivityMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品与活动查询接口
 *
 * @author 向国豪
 */
@RestController
@RequestMapping("/goods")
@RequiredArgsConstructor
@Tag(name = "商品", description = "商品与秒杀活动查询")
public class GoodsController {

    private final GoodsMapper goodsMapper;
    private final SeckillActivityMapper activityMapper;

    @GetMapping("/list")
    @Operation(summary = "商品列表")
    public Result<List<Goods>> list() {
        return Result.success(goodsMapper.selectList(
                new LambdaQueryWrapper<Goods>().orderByAsc(Goods::getId)));
    }

    @GetMapping("/activity/list")
    @Operation(summary = "秒杀活动列表")
    public Result<List<SeckillActivity>> activityList() {
        return Result.success(activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().orderByAsc(SeckillActivity::getId)));
    }

    @GetMapping("/activity/{id}")
    @Operation(summary = "秒杀活动详情", description = "包含商品信息和实时剩余库存")
    public Result<Map<String, Object>> activityDetail(@PathVariable Long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            return Result.fail(com.xgh.seckill.common.result.ResultCode.NOT_FOUND);
        }
        Goods goods = goodsMapper.selectById(activity.getGoodsId());

        Map<String, Object> data = new HashMap<>();
        data.put("activity", activity);
        data.put("goods", goods);
        return Result.success(data);
    }
}
