package com.example.runtime

import com.example.model.TreeNode
import org.babyfish.jimmer.sql.fetcher.RecursionStrategy

class ChildNodesRecursionStrategy : RecursionStrategy<TreeNode> {
    override fun isRecursive(args: RecursionStrategy.Args<TreeNode>): Boolean =
        args.entity.name != "Clothing"
}
