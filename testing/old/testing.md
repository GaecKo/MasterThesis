# Testing plan
Testing plan proposal (high level) - no precise metrics etc etc 

For both part, interesting to see the graph latency VS throughput
But also a more simple graph with time period as x axis and y the req/s for instance 

## Context 1: RTT - backend <-> (gateway) <-> devices
Test whole flow
In this context, everything is enabled on the gateway, security, authN/Z, ...
The point is to check the average time taken - not checking perf of gateway under overload context

### Scenario 1: No API Gateway
Test perf for communication between backend and device without the gateway 

### Scenario 2: API Gateway (single threaded) (Optional - to confirm)
Test perf for communication with backend <-> gateway <-> device using a single thread on the API Gateway 
Is it really relevant? Don't we want to test our final production as is ? Ask supervisors

### Scenario 3: API Gateway (multi threaded)
Test perf for communication with backend <-> gateway <-> device using multi thread on the API Gateway 

## Context 2: Gateway Overload testing 
Test the performance of the API Gateway - ideally per filter (Auth filter - Translation Filter)
Would be cool to have a graph with x axis = a time period and y axis in which filter the request is being processed (would be 2 values here), we would see first the request in Auth filter for a certain period of time, and then gets to translation filter
In this part, security (rate limitation / TOP) is always disabled, as we test under overload scenario

For all these part, we could consider having 4 lines on a graph (using dashed - full lines to differentiate multi / single threading)

### Scenario 1: Auth filter + multithread
Full API Gateway: Auth Filter, multithread 

## Scenario 2: Auth filter and single thread

## Scenario 3: No Auth Filter and multithread 

### Scenario 4: No Auth Filter and single thread



# Other test
We have to test security: confirm that with rate limitation, we see an upper limit 
Also confirm that IP blacklisting occurs 
