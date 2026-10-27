FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY vault_server.py .

ENV DATA_DIR=/data
EXPOSE 8765
CMD ["python", "-u", "vault_server.py"]
