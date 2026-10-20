package com.example.model

import org.babyfish.jimmer.sql.*

@Entity
interface TreeNode {

    @Id
    @Column(name = "NODE_ID")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key
    val name: String

    @Key
    @ManyToOne
    @OnDissociate(DissociateAction.DELETE)
    val parent: TreeNode?

    @OneToMany(mappedBy = "parent", orderedProps = [OrderedProp("name")])
    val childNodes: List<TreeNode>
}
