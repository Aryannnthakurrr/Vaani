# saathi-skill-sandbox: runs compiled Python skills (docs/LEARNING.md §1). Started per job with
#   --network none --read-only --tmpfs /tmp:size=4m --memory 128m --cpus 0.5 --pids-limit 32
#   --cap-drop ALL --security-opt no-new-privileges --user 65534:65534
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY runner.py /runner.py
RUN chmod 0444 /runner.py

USER 65534:65534
WORKDIR /tmp
ENTRYPOINT ["python", "-I", "/runner.py"]
