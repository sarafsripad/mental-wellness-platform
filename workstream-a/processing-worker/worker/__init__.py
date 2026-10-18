"""Audio-processing worker: claims jobs from the wellness-api, runs quality control and openSMILE
feature extraction on leased audio, and submits feature records (slice step S7).

The worker never holds database or storage credentials, keeps audio in memory only, and logs IDs,
durations and error codes only (never audio, feature values or file paths).
"""

__version__ = "0.1.0"
