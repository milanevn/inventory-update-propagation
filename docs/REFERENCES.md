# Sources and provenance

## Assignment / instructor text
- Uploaded image `DeTai_DoAn.png`, assignment 8: Client ↔ S1; S1→S2 immediate; S1→S3 after n seconds; S2→S4 after n updates; recovery when data is damaged.
- Clarification from the team's earlier course discussion: S1→S2 does not require waiting for receiver ACK; readers may access replicas. Verify against instructor if changed.

## Uploaded lecture slides used as conceptual basis
- `Noi dung 2 - truyen thong dinh danh(6).pptx`: RPC and messaging characteristics.
- `Noi dung 3 - Thoi gian va dong ho vat ly(6).pptx`: logical ordering vs physical clock skew.
- `Noi dung 7 -  Loai tru tuong ho- giai thuat bau cu(6).pptx`: coordinator/election concepts and mutex.
- `Noi dung 11 - quan tri giao dich va dieu khien tuong tranh(6).pptx`: ACID, locking, concurrent-write conflicts.
- `Noi dung 12 - phuc hoi va chiu loi(6).pptx`: fault models, operation and checkpoint recovery.
- `Noi dung 13 - Bao mat(6).pptx`: confidentiality, integrity, availability and access controls.
- `Noi dung 14 - Nhat quan va nhan ban(6).pptx`: primary-based replication, delayed propagation and consistency.
- `Noi dung 15 CORBA & RMI(6).pptx`: middleware, remote-object interfaces.

## Engineering decisions not explicitly mandated by slides
Transactional outbox, durable idempotent consumer, gRPC/Protobuf, schema/namespace, precise ACK statuses, fail-closed promotion policy, Spring Boot, JDBC/Flyway, Docker local and CI. These are recommendations to meet the assignment under the schedule, **not** claims that the lecturer prescribed them.

**External verification caveat:** Dependency versions are pinned to known coordinates but could not be resolved/validated from remote Maven repositories in this offline generation environment. Validate with Maven and consult official version/security notes before beginning work.
