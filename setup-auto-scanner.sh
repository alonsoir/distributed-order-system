#!/bin/bash
# Setup script para SonarQube Smell Fixer

echo "🚀 Configurando SonarQube Smell Fixer..."

# 1. Instalar Ollama si no está instalado
if ! command -v ollama &> /dev/null; then
    echo "📦 Instalando Ollama..."
    curl -fsSL https://ollama.ai/install.sh | sh
else
    echo "✅ Ollama ya está instalado"
fi

# 2. Descargar modelo de código
echo "🤖 Descargando modelo CodeLlama..."
ollama pull codellama:7b

# 3. Instalar dependencias Python
echo "🐍 Instalando dependencias Python..."
pip install requests openai pathlib

# 4. Crear archivo de configuración
echo "⚙️  Creando archivo de configuración..."
cat > config.json << EOF
{
    "sonar_url": "http://localhost:9000",
    "sonar_token": "REPLACE_WITH_YOUR_TOKEN",
    "project_key": "REPLACE_WITH_YOUR_PROJECT_KEY",
    "llm_api_base": "http://localhost:11434/v1",
    "test_command": "npm test",
    "file_extensions": [".js", ".ts", ".java", ".py", ".cpp", ".c", ".cs"]
}
EOF

echo "✅ Setup completado!"
echo ""
echo "📝 Pasos siguientes:"
echo "1. Edita config.json con tu token de SonarQube y project key"
echo "2. Asegúrate de que SonarQube esté corriendo en localhost:9000"
echo "3. Ejecuta: python sonar_smell_fixer.py"
echo ""
echo "🔑 Para obtener tu token de SonarQube:"
echo "   - Ve a http://localhost:9000"
echo "   - User → My Account → Security → Generate Token"
echo ""
echo "🏷️  Para obtener tu project key:"
echo "   - Ve a tu proyecto en SonarQube"
echo "   - Project Settings → encontrarás el key ahí"