create table lote (
    id number(18) generated always as identity,
    estado varchar2(12 char) default 'IMPORTADO' not null,
    criado_em timestamp(6) default sys_extract_utc(systimestamp) not null,
    validado_em timestamp(6),
    aprovado_em timestamp(6),
    migrado_em timestamp(6),
    motor varchar2(6 char),
    validos number(10),
    invalidos number(10),
    constraint pk_lote primary key (id),
    constraint ck_lote_estado check (estado in ('IMPORTADO','INVALIDO','VALIDADO','APROVADO','MIGRADO')),
    constraint ck_lote_motor check (motor in ('JAVA','PLSQL'))
);

create table cliente (
    id number(18) generated always as identity,
    codigo varchar2(30 char) not null,
    nome varchar2(120 char) not null,
    email varchar2(254 char) not null,
    constraint pk_cliente primary key (id),
    constraint uk_cliente_codigo unique (codigo)
);

create table contrato (
    id number(18) generated always as identity,
    codigo varchar2(30 char) not null,
    cliente_id number(18) not null,
    inicio date not null,
    fim date not null,
    valor number(12,2) not null,
    constraint pk_contrato primary key (id),
    constraint uk_contrato_codigo unique (codigo),
    constraint fk_contrato_cliente foreign key (cliente_id) references cliente(id),
    constraint ck_contrato_periodo check (fim >= inicio),
    constraint ck_contrato_valor check (valor > 0)
);
create index ix_contrato_cliente on contrato(cliente_id);

create table staging_registro (
    lote_id number(18) not null,
    entidade varchar2(8 char) not null,
    linha number(10) not null,
    codigo varchar2(1000 char),
    nome varchar2(1000 char),
    email varchar2(1000 char),
    cliente_codigo varchar2(1000 char),
    inicio_txt varchar2(1000 char),
    fim_txt varchar2(1000 char),
    valor_txt varchar2(1000 char),
    erro_csv varchar2(300 char),
    constraint pk_staging primary key (lote_id, entidade, linha),
    constraint fk_staging_lote foreign key (lote_id) references lote(id),
    constraint ck_staging_entidade check (entidade in ('CLIENTE','CONTRATO')),
    constraint ck_staging_linha check (linha >= 2)
);

create table erro_validacao (
    lote_id number(18) not null,
    entidade varchar2(8 char) not null,
    linha number(10) not null,
    campo varchar2(30 char) not null,
    codigo varchar2(30 char) not null,
    motivo varchar2(200 char) not null,
    constraint pk_erro primary key (lote_id, entidade, linha, campo, codigo),
    constraint fk_erro_staging foreign key (lote_id, entidade, linha)
        references staging_registro(lote_id, entidade, linha)
);

create table controle_migracao (
    id number(1) constraint pk_controle_migracao primary key,
    constraint ck_controle_migracao check (id = 1)
);
insert into controle_migracao(id) values (1);
commit;
