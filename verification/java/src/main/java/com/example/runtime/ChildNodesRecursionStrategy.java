package com.example.runtime;

import com.example.model.TreeNode;
import org.babyfish.jimmer.sql.fetcher.RecursionStrategy;

public class ChildNodesRecursionStrategy implements RecursionStrategy<TreeNode> {
    @Override
    public boolean isRecursive(Args<TreeNode> args) {
        return !args.getEntity().name().equals("Clothing");
    }
}
