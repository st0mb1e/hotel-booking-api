create table reservation_guard (
    id bigint primary key check (id = 1)
);
insert into reservation_guard(id) values (1);

alter table room_reservations alter column room_id drop not null;
alter table room_reservations add column auto_selected boolean not null default false;
alter table room_reservations add column hotel_filter_id bigint;
alter table room_reservations add column version bigint not null default 0;
alter table room_reservations add constraint chk_reserved_room
    check (status = 'RELEASED' or room_id is not null);
