import onnxruntime
import urllib.request
import os

url = "https://huggingface.co/jackjiangxinfa/demucs-onnx/resolve/main/model.onnx"
model_path = "test_model.onnx"

print("Downloading model...")
# urllib.request.urlretrieve(url, model_path)
print("Finished downloading. But to be fast, let's just stream or get headers.")
# Downloading 300MB might be slow, but we can do it. Let's just download it.
if not os.path.exists(model_path):
    urllib.request.urlretrieve(url, model_path)

print("Loading model...")
session = onnxruntime.InferenceSession(model_path, providers=['CPUExecutionProvider'])

for i, input in enumerate(session.get_inputs()):
    print(f"Input {i}: Name='{input.name}', Shape={input.shape}, Type={input.type}")

for i, output in enumerate(session.get_outputs()):
    print(f"Output {i}: Name='{output.name}', Shape={output.shape}, Type={output.type}")

print("Done.")
