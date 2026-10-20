create table book_store(id bigint auto_increment primary key, name varchar(50) not null unique, website varchar(100));
create table book(id bigint auto_increment primary key, name varchar(50) not null, edition int not null, price numeric(10,2) not null, modified_time timestamp not null, store_id bigint references book_store(id), unique(name, edition));
create table author(id bigint auto_increment primary key, first_name varchar(25) not null, last_name varchar(25) not null, unique(first_name, last_name));
create table book_author_mapping(book_id bigint not null references book(id), author_id bigint not null references author(id), primary key(book_id, author_id));
insert into book_store(name) values('O''REILLY'), ('MANNING');
insert into book(name, edition, price, modified_time, store_id) values('Learning GraphQL', 1, 50, now(), 1), ('Learning GraphQL', 2, 55, now(), 1), ('GraphQL in Action', 1, 80, now(), 2);
insert into author(first_name, last_name) values('Eve', 'Procello'), ('Alex', 'Banks'), ('Samer', 'Buna');
insert into book_author_mapping values(1, 1), (1, 2), (2, 1), (3, 3);
