package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * 本地演示用管理接口（仅 app.sim.enabled=true 时装配）。
 * <ul>
 *   <li>POST /sim-admin/produce?count=1 —— 出块；</li>
 *   <li>POST /sim-admin/reorg?depth=2 —— 在指定深度重组；</li>
 *   <li>POST /sim-admin/nodes/{nodeId}/behavior?behavior=TIMEOUT&amp;lagBlocks=0；</li>
 *   <li>GET  /sim-admin/tip —— 查看模拟链链尖。</li>
 * </ul>
 */
@RestController
public class SimAdminController {

    private final SimulatedChain chain;
    private final SimBehaviorRegistry behaviors;

    public SimAdminController(SimulatedChain chain, SimBehaviorRegistry behaviors) {
        this.chain = chain;
        this.behaviors = behaviors;
    }

    @PostMapping("/sim-admin/produce")
    public Map<String, Object> produce(@RequestParam(defaultValue = "1") int count,
                                       @RequestParam(required = false) String payload) {
        int n = Math.max(1, count);
        Block last = null;
        for (int i = 0; i < n; i++) {
            last = chain.produceBlock(payload == null ? ("admin-" + System.nanoTime()) : payload);
        }
        return Map.of("produced", n, "tipHeight", chain.tipHeight(),
                "tipHash", last == null ? "" : last.hash());
    }

    @PostMapping("/sim-admin/reorg")
    public Map<String, Object> reorg(@RequestParam int depth) {
        long forkHeight = chain.reorg(depth);
        if (forkHeight < 0) {
            return Map.of("ok", false, "message",
                    "depth 非法（需在 1..tipHeight+1 之间），tipHeight=" + chain.tipHeight());
        }
        Block tip = chain.blockAt(chain.tipHeight());
        return Map.of("ok", true, "forkHeight", forkHeight,
                "tipHeight", chain.tipHeight(),
                "tipHash", tip == null ? "" : tip.hash());
    }

    @PostMapping("/sim-admin/nodes/{nodeId}/behavior")
    public Map<String, Object> setBehavior(@PathVariable String nodeId,
                                           @RequestParam SimBehavior behavior,
                                           @RequestParam(defaultValue = "0") int lagBlocks) {
        behaviors.setBehavior(nodeId, behavior, lagBlocks);
        return Map.of("nodeId", nodeId, "behavior", behavior, "lagBlocks", lagBlocks);
    }

    @GetMapping("/sim-admin/tip")
    public Map<String, Object> tip() {
        Block tip = chain.blockAt(chain.tipHeight());
        return Map.of("tipHeight", chain.tipHeight(),
                "tipHash", tip == null ? "" : tip.hash());
    }
}
