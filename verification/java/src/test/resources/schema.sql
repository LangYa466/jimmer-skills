drop all objects;

create table book_store(
    id bigint auto_increment primary key,
    name varchar(50) not null,
    website varchar(100),
    version int not null default 0,
    created_time timestamp not null,
    modified_time timestamp not null,
    constraint uq_book_store unique(name)
);

create table book(
    id bigint auto_increment primary key,
    name varchar(50) not null,
    edition int not null,
    price numeric(10, 2) not null,
    store_id bigint,
    tenant varchar(20) not null,
    created_time timestamp not null,
    modified_time timestamp not null,
    constraint uq_book unique(name, edition),
    constraint fk_book__store foreign key(store_id) references book_store(id)
);

create table author(
    id bigint auto_increment primary key,
    first_name varchar(25) not null,
    last_name varchar(25) not null,
    gender char(1) not null,
    deleted boolean not null default false,
    created_time timestamp not null,
    modified_time timestamp not null,
    constraint uq_author unique(first_name, last_name)
);

create table book_author_mapping(
    book_id bigint not null,
    author_id bigint not null,
    constraint pk_book_author_mapping primary key(book_id, author_id),
    constraint fk_bam__book foreign key(book_id) references book(id) on delete cascade,
    constraint fk_bam__author foreign key(author_id) references author(id) on delete cascade
);

create table tree_node(
    node_id bigint auto_increment primary key,
    name varchar(20) not null,
    parent_id bigint,
    constraint uq_tree_node unique(name, parent_id),
    constraint fk_tree_node__parent foreign key(parent_id) references tree_node(node_id)
);

insert into book_store(name, website, created_time, modified_time) values
('O''REILLY', null, now(), now()),
('MANNING', null, now(), now());

insert into book(name, edition, price, store_id, tenant, created_time, modified_time) values
('Learning GraphQL', 1, 50.00, 1, 'a', now(), now()),
('Learning GraphQL', 2, 55.00, 1, 'b', now(), now()),
('Learning GraphQL', 3, 51.00, 1, 'a', now(), now()),
('Effective TypeScript', 1, 73.00, 1, 'b', now(), now()),
('GraphQL in Action', 1, 80.00, 2, 'a', now(), now()),
('GraphQL in Action', 2, 81.00, 2, 'b', now(), now());

insert into author(first_name, last_name, gender, created_time, modified_time) values
('Eve', 'Procello', 'F', now(), now()),
('Alex', 'Banks', 'M', now(), now()),
('Dan', 'Vanderkam', 'M', now(), now()),
('Samer', 'Buna', 'M', now(), now());

insert into book_author_mapping(book_id, author_id) values
(1, 1), (1, 2), (2, 1), (2, 2), (3, 1), (3, 2), (4, 3), (5, 4), (6, 4);

insert into tree_node(name, parent_id) values ('Home', null);
insert into tree_node(name, parent_id) values ('Food', 1), ('Clothing', 1);
insert into tree_node(name, parent_id) values ('Drinks', 2), ('Bread', 2), ('Woman', 3), ('Man', 3);
insert into tree_node(name, parent_id) values ('Coca Cola', 4), ('Fanta', 4);
