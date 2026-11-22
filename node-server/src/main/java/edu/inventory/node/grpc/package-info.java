/**
 * gRPC transport layer: lifecycle of the node's gRPC server and thin adapters that extend the
 * generated {@code *ImplBase} classes. Adapters map protobuf to domain types and call the module
 * packages; they contain no SQL and no business rules.
 *
 * <p>Any Spring bean implementing {@link io.grpc.BindableService} is registered automatically by
 * {@link edu.inventory.node.grpc.GrpcServerLifecycle}.
 */
package edu.inventory.node.grpc;
