package com.xgh.seckill.common.exception;

import com.xgh.seckill.common.result.Result;
import com.xgh.seckill.common.result.ResultCode;
import com.xgh.seckill.component.SeckillMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * 【与监控的联动】所有异常在这里统一埋点，好处是：
 *   - 业务代码不需要到处写 metrics.recordXxx()，保持干净
 *   - 指标口径绝对一致：只要经过这里，就一定被统计到，不会漏
 * 这是 AOP / 统一入口的价值——把横切关注点收拢到一处。
 *
 * @author 向国豪
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final SeckillMetrics metrics;

    /** 业务异常：预期内，用 warn 级别，不打堆栈 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常 uri={} code={} msg={}", request.getRequestURI(), e.getCode(), e.getMessage());

        // 【指标埋点】区分「业务失败」与「系统异常」是监控的关键：
        // 秒杀场景下 602 售罄、603 重复下单 都是正常业务结果，不能算故障；
        // 只有 5xx / 未捕获异常才是真正需要告警的。
        metrics.recordFailure(e.getResultCode());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验异常（@RequestBody + @Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        metrics.recordFailure(ResultCode.PARAM_ERROR);
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /** 参数绑定异常（表单） */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        metrics.recordFailure(ResultCode.PARAM_ERROR);
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /** 缺少必要参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        metrics.recordFailure(ResultCode.PARAM_ERROR);
        return Result.fail(ResultCode.PARAM_ERROR, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 请求方式不支持（如用 GET 调 POST 接口）
     *
     * 【为什么单独处理】这是客户端用法错误，不是服务端故障。
     * 若不单独处理，会被兜底成 500「系统繁忙」，把「接口用错了」
     * 伪装成「服务器挂了」，排查时极具误导性。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        log.warn("请求方式不支持 uri={} method={} 支持={}",
                request.getRequestURI(), e.getMethod(), e.getSupportedHttpMethods());
        return Result.fail(ResultCode.PARAM_ERROR,
                "请求方式不支持：" + e.getMethod() + "，请改用 " + e.getSupportedHttpMethods());
    }

    /**
     * 静态资源 / 路由不存在
     *
     * 【踩坑记录】Spring Boot 3 中，未被任何 @RequestMapping 匹配的路径，
     * 会被交给 ResourceHttpRequestHandler 当作静态资源查找，
     * 找不到就抛 NoResourceFoundException。
     *
     * 如果不单独处理，它会掉进下面的 Exception 兜底分支，
     * 结果是：把 URL 写错（404 语义）计入了 systemError 指标，
     * 导致 systemErrorRate 虚高、告警失焦——这正是本项目实际踩过的坑。
     *
     * 正确的做法是识别为「客户端错误」，返回 404 语义且不计入系统异常。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e,
            HttpServletRequest request) {
        log.warn("接口不存在 uri={}（请检查 URL 与请求方式）", request.getRequestURI());
        return Result.fail(ResultCode.NOT_FOUND, "接口不存在：" + request.getRequestURI());
    }

    /** 兜底异常：非预期，打完整堆栈，方便排查 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 uri={}", request.getRequestURI(), e);
        // 【关键区分】这是「我们自己的锅」，必须单独计数
        metrics.recordSystemError();
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
