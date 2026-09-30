package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMgisQualLineMapper;
import com.fc.v2.model.auto.TMgisQualLine;
import com.fc.v2.service.ITMgisQualLineService;

/**
 * 测绘成果质检判定线 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMgisQualLineServiceImpl implements ITMgisQualLineService {

    /** 线的情形 0当家 1已让位：只有当家线参与判定 */
    private static final int STATUS_ACTIVE = 0;

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TMgisQualLine> PRIORITY_THEN_CODE_DESC = new Comparator<TMgisQualLine>() {
        @Override
        public int compare(TMgisQualLine a, TMgisQualLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TMgisQualLineMapper mgisQualLineMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public TMgisQualLine selectTMgisQualLineById(Long id) {
        return this.mgisQualLineMapper.selectById(id);
    }

    @Override
    public List<TMgisQualLine> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TMgisQualLine>();
        }
        List<TMgisQualLine> rows = this.mgisQualLineMapper.selectList(new QueryWrapper<TMgisQualLine>()
                .eq("del_flag", 0)
                .eq("status", STATUS_ACTIVE));
        List<TMgisQualLine> avail = new java.util.ArrayList<TMgisQualLine>();
        for (TMgisQualLine r : rows) {
            if (inEffect(r, at)) {
                avail.add(r);
            }
        }
        // 口径与 evaluateTop 一致：优先级高者在前
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        TMgisQualLine rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<TMgisQualLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TMgisQualLine r = this.mgisQualLineMapper.selectById(id);
        // 口径须与定位/列表一致：未删除、当家、且 at 落在生效窗口内
        return r != null
                && (r.getDelFlag() == null || r.getDelFlag() == 0)
                && r.getStatus() != null && r.getStatus() == STATUS_ACTIVE
                && inEffect(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    /** at 时刻生效：启用之日（含）起，至交棒之日（不含）止；交棒日为空表示长期当家 */
    private static boolean inEffect(TMgisQualLine r, Date at) {
        String now = ts(at);
        if (r.getEffStart() != null && ts(r.getEffStart()).compareTo(now) > 0) {
            return false;
        }
        if (r.getEffEnd() != null && ts(r.getEffEnd()).compareTo(now) <= 0) {
            return false;
        }
        return true;
    }

    /** 按 ruleCode 定位 at 时刻生效的版本；多版本抢档时与 evaluateTop 同一顺位口径 */
    private TMgisQualLine findRule(String ruleCode, Date at) {
        List<TMgisQualLine> hit = this.mgisQualLineMapper.selectList(new QueryWrapper<TMgisQualLine>()
                .eq("del_flag", 0)
                .eq("status", STATUS_ACTIVE)
                .eq("rule_code", ruleCode));
        List<TMgisQualLine> avail = new java.util.ArrayList<TMgisQualLine>();
        for (TMgisQualLine r : hit) {
            if (inEffect(r, at)) {
                avail.add(r);
            }
        }
        if (avail.isEmpty()) {
            return null;
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail.get(0);
    }

    /** 分档：取值达到本档上限即进高一档（严格小于才留本档） */
    private int levelOf(TMgisQualLine rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) < 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) < 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
