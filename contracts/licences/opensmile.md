# openSMILE — licence position

**Recorded:** 2026-10-18
**Decided by:** repository owner (GitHub `sarafsripad`), as responsible engineer
**Status:** Accepted for **development and evaluation only**. Legal review pending.

## What we use

| Item | Value |
|---|---|
| Python package | `opensmile` 2.6.0 (PyPI, audEERING) |
| Native core | openSMILE 3.0.1 (`libSMILEapi.so`, built 2023-10-19), bundled with the package |
| Feature set | eGeMAPS v02, functionals (`EGEMAPS_V02_FUNCTIONALS_V1` pipeline profile) |
| Config file | `opensmile/core/config/egemaps/v02/eGeMAPSv02.conf`, SHA-256 `ef451953badced2ed112ba4ffb997f9d2a7e7c444a09e8092665eadd9f24109e` |
| Used by | `workstream-a/processing-worker` only |

## Licence

The package ships the **audEERING Research License Agreement**. (Its PyPI classifier says "MIT License", but the licence text in the package is the research licence, so that text governs.) In summary:

- **Allowed:** non-commercial use, such as teaching, academic research, public demonstrations and personal experimentation.
- **Allowed with conditions:** commercial research that is "not directly associated with product development", and "pre-product evaluations of algorithms and methods … of an evaluatory, planning, and research nature".
- **Not allowed without a commercial licence or written approval from audEERING:** any other commercial use, including *"any direct (software) or indirect (models, features extracted with the software) use … in a product"*.
- **Obligations:** acknowledge openSMILE in publications (see the package's CITING file), keep copyright notices, don't modify or reverse-engineer the binaries, and pass on the disclaimer if redistributing.

## Our position

1. Building and testing the processing pipeline with **synthetic** or **authorised test audio** (`data_origin` `SYNTHETIC` or `AUTHORIZED_TEST_AUDIO`) is treated as pre-product evaluation. This is the only use currently permitted.
2. **Not permitted** until a commercial licence or written approval exists:
   - running the worker in any production environment
   - processing participant data for the product
   - using features extracted with openSMILE to train or ship models in a product
3. The worker enforces point 1 in code: it refuses jobs whose `data_origin` isn't in `WORKER_ALLOWED_ORIGINS` (default `SYNTHETIC,AUTHORIZED_TEST_AUDIO`).
4. Container images that include openSMILE must not be published publicly, because redistribution carries the licence's conditions.

## Open items

- [ ] Legal review of this position, including whether the screening programme's research phase qualifies as "commercial research" under clause B.
- [ ] Contact audEERING (info@audeering.com) about commercial licensing before any production use.
- [ ] Decide whether to keep openSMILE for production or switch extractors. Any switch is a new pipeline profile and feature set, and needs a contract review.
