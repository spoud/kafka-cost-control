SELECT * FROM aggregated_data
WHERE start_time >= ? AND end_time <= ?
    {#if metricNames.size > 0}
    AND initial_metric_name IN (
        {#each metricNames}
            ?{#if it_hasNext}, {/if}
        {/each}
    )
    {/if}
