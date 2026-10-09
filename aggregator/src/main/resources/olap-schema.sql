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

-- Costs are computed when asked, in the costs view. Older databases still have the cost column
-- that the stream filled with the pricing rule cost at the time: it is dropped in place.
ALTER TABLE aggregated_data DROP COLUMN IF EXISTS cost;
