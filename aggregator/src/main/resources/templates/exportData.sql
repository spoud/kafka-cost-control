COPY(
    SELECT *
    FROM aggregated_data
    WHERE start_time >= ? AND end_time <= ?
) TO '{tmpFileName}'
{#if finalFormat == 'csv'}
    (HEADER, DELIMITER ',')
{/if}
