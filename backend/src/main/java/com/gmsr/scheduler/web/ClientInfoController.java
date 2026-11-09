package com.gmsr.scheduler.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端环境识别。
 *
 * 前端需要知道「这是不是手机」，才能决定用哪套布局：
 * 手机上没有鼠标，排班页原本依赖的 HTML5 拖拽根本无法触发，
 * 必须换成「点选 → 点目标」的方式，所以不能只靠 CSS 媒体查询（那只改样式，改不了交互）。
 *
 * 识别用 User-Agent 请求头。浏览器出于隐私会把 UA 简化（Chrome 的 UA-CH），
 * 但「是不是手机」这一位仍然可靠，因为它决定了网站返回移动版还是桌面版。
 * 前端另外还会用视口宽度兜底，两侧判断结合。
 */
@RestController
@RequestMapping("/api")
public class ClientInfoController {

    /** 手机（含平板手机、功能机）；不含 iPad 等大屏设备。 */
    private static final Pattern MOBILE = Pattern.compile(
            "Mobile|Android.*Mobile|iPhone|iPod|Windows Phone|IEMobile|BlackBerry|Opera Mini",
            Pattern.CASE_INSENSITIVE);

    /** 平板：单独的类别，布局上介于手机和桌面之间。 */
    private static final Pattern TABLET = Pattern.compile(
            "iPad|Tablet|Android(?!.*Mobile)|Silk|Kindle|PlayBook",
            Pattern.CASE_INSENSITIVE);

    /** 触屏设备（决定要不要用点选交互而不是拖拽）。 */
    private static final Pattern TOUCH = Pattern.compile(
            "Mobile|Android|iPhone|iPad|iPod|Touch|Tablet",
            Pattern.CASE_INSENSITIVE);

    @GetMapping("/client-info")
    public Map<String, Object> clientInfo(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        String uaString = ua == null ? "" : ua;

        String device;
        if (MOBILE.matcher(uaString).find()) {
            device = "mobile";
        } else if (TABLET.matcher(uaString).find()) {
            device = "tablet";
        } else {
            device = "desktop";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", device);
        // 服务端只做粗判；前端会结合视口宽度与「切换到电脑版」的偏好做最终决定
        result.put("isMobile", "mobile".equals(device));
        result.put("isTablet", "tablet".equals(device));
        result.put("touch", TOUCH.matcher(uaString).find()
                || "mobile".equals(device) || "tablet".equals(device));
        result.put("ua", uaString.length() > 300 ? uaString.substring(0, 300) : uaString);
        return result;
    }
}
