## Mô tả

<!-- Thay đổi gì và vì sao. Liên kết issue: Closes #… -->

## Thay đổi hợp đồng và schema

- [ ] Không thay đổi `.proto` hoặc schema
- [ ] Có thay đổi `.proto` hoặc schema; `docs/thiet-ke.md` đã được cập nhật
- [ ] Có migration mới `V<n>__…sql` (migration đã phát hành không bị sửa)

## Kiểm thử

- [ ] `mvnw verify` thành công, bao gồm integration test
- [ ] Có test cho trường hợp thành công, retry và đầu vào không hợp lệ
- [ ] Kịch bản nghiệm thu liên quan: T…
- [ ] Log thể hiện `node_id`, `seq`, `event_id` (nếu liên quan)

## Hành vi khi lỗi, migration và rollback

<!-- Hệ thống phản ứng thế nào khi mất mạng, crash hoặc dữ liệu không hợp lệ; cách rollback nếu cần. -->
