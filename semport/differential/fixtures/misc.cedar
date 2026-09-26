@id("first")
@doc("x")
permit (principal == User::"alice", action == Action::"view", resource in NS::Album::"a") // User::"not"
when { context.x == NS::Action::"edit" };

@custom
@other("y")
forbid (principal, action in [Action::"a", Action::"b"], resource)
unless { principal has x };
@cdkId("cdk")
permit (principal, action, resource)
