package com.xgh.seckill.common.exception;

import com.xgh.seckill.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 *
 * @author 向国豪
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    /**
     * 保留原始错误码枚举对象（可为 null）
     *
     * 【为什么要多存一个枚举，而不是只用 code？】
     * 因为监控埋点需要「按错误码语义分类」。
     * 只有 Integer code 时，统计出来是一堆裸数字；
     * 带上枚举后，指标里可以直接显示 "602:商品已售罄"，
     * 一眼就能看出失败原因分布——这正是运维最需要的信息。
     */
    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    public BizException(String message) {
        super(message);
        this.code = ResultCode.SYSTEM_ERROR.getCode();
        this.resultCode = ResultCode.SYSTEM_ERROR;
    }

    /** 不填充堆栈，提升性能（秒杀场景异常量大，堆栈填充是明显开销） */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
