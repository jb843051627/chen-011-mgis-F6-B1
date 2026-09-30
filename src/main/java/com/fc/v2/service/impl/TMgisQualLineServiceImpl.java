package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    /** 合法输入闭区间：中误差限差以米计，越界一律按不可判（0）处理 */
    private static final BigDecimal INPUT_MIN = BigDecimal.ZERO;
    private static final BigDecimal INPUT_MAX = new BigDecimal("100");

    @javax.annotation.Resource
    private TMgisQualLineMapper mgisQualLineMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public TMgisQualLine selectTMgisQualLineById(Long id) {
        return this.mgisQualLineMapper.selectById(id);
    }

    /**
     * at 时刻可用规则，与 {@link #usable(Long, Date)} / {@link #countAvailable(Date)} 同一口径：
     * 未删（del_flag=0）、当家（status=0）、生效窗口含 at（eff_start &lt;= at &lt; eff_end，
     * 交棒日不含；起点为空视为自古生效，终点为空视为长期有效）。
     * 返回按优先级降序、ruleCode 降序排定。
     */
    @Override
    public List<TMgisQualLine> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TMgisQualLine>();
        }
        List<TMgisQualLine> all = this.mgisQualLineMapper.selectList(
                new QueryWrapper<TMgisQualLine>().eq("del_flag", 0));
        List<TMgisQualLine> avail = new ArrayList<TMgisQualLine>();
        for (TMgisQualLine r : all) {
            if (isUsable(r, at)) {
                avail.add(r);
            }
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(INPUT_MIN) < 0 || input.compareTo(INPUT_MAX) > 0) {
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
        if (input.compareTo(INPUT_MIN) < 0 || input.compareTo(INPUT_MAX) > 0) {
            return 0;
        }
        List<TMgisQualLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    /** 单条规则在 at 时刻是否可用：与列表/计数同炉，不另立判法 */
    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        return isUsable(this.mgisQualLineMapper.selectById(id), at);
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    /**
     * 按 ruleCode 定位 at 时刻的当家版本：同代号多版本时取启用之日最新者；
     * 没有任何生效版本返回 null（调用方按不可用处理）。
     */
    private TMgisQualLine findRule(String ruleCode, Date at) {
        List<TMgisQualLine> hit = this.mgisQualLineMapper.selectList(new QueryWrapper<TMgisQualLine>()
                .eq("del_flag", 0).eq("rule_code", ruleCode));
        TMgisQualLine newest = null;
        for (TMgisQualLine r : hit) {
            if (!isUsable(r, at)) {
                continue;
            }
            if (newest == null
                    || (r.getEffStart() != null && newest.getEffStart() != null
                            && r.getEffStart().after(newest.getEffStart()))
                    || (r.getEffStart() != null && newest.getEffStart() == null)) {
                newest = r;
            }
        }
        return newest;
    }

    /** 唯一口径：在库未删、当家未让位、at 落在生效窗口内（启用日含、交棒日不含） */
    private static boolean isUsable(TMgisQualLine r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() != 0) {
            return false;
        }
        String now = ts(at);
        if (r.getEffStart() != null && now.compareTo(ts(r.getEffStart())) < 0) {
            return false;
        }
        if (r.getEffEnd() != null && now.compareTo(ts(r.getEffEnd())) >= 0) {
            return false;
        }
        return true;
    }

    /** 分档：等于上限取高一档（取值达到上限时按高一档折算），阈值缺失的档不参与判定 */
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
