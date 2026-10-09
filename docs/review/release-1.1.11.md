# 1.1.11 review consolidation

Combines the completed transport/output-boundary review fixes with the newer main lifecycle, shared HTTP resources, half-close protocol, parsing budgets and pinned generator. The older private generator patch pipeline is superseded by the reviewed upstream generator pinned in gradle/protobuf-tools.properties.

Unary HTTP status metadata is validated exactly, including duplicate/conflicting headers and successful HTTP responses carrying invalid RPC status. Output budgets are shared across nested encoders; failures after partial writes remain terminal, while unchanged invalid-field preflight failures preserve the output. Encoding-limit failures remain permanent OUT_OF_RANGE; existing decoding/admission RESOURCE_EXHAUSTED behavior is retained.

Client disposal retains cancellation-complete operation ownership without shutting down shared transports. A closed client rejects calls with typed CANCELLED status. Existing public constructor and wire signatures remain compatible.
