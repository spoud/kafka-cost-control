CREATE TABLE IF NOT EXISTS aggregated_data
(
    start_time          TIMESTAMPTZ NOT NULL,
    end_time            TIMESTAMPTZ NOT NULL,
    initial_metric_name VARCHAR     NOT NULL,
    entity_type         VARCHAR     NOT NULL,
    name                VARCHAR     NOT NULL,
    tags                JSON        NOT NULL,
    context             JSON        NOT NULL,
    value               DOUBLE      NOT NULL,
    target              VARCHAR     NOT NULL,
    id                  VARCHAR PRIMARY KEY
);
