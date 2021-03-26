package manifests

import com.google.gson.annotations.SerializedName

data class Manifest(

	@field:SerializedName("schemaVersion")
	val schemaVersion: Int? = null,

	@field:SerializedName("layers")
	val layers: List<LayersItem?>? = null,

	@field:SerializedName("mediaType")
	val mediaType: String? = null,

	@field:SerializedName("config")
	val config: Config? = null
)

data class LayersItem(

	@field:SerializedName("size")
	val size: Int? = null,

	@field:SerializedName("digest")
	val digest: String? = null,

	@field:SerializedName("mediaType")
	val mediaType: String? = null
)

data class Config(

	@field:SerializedName("size")
	val size: Int? = null,

	@field:SerializedName("digest")
	val digest: String? = null,

	@field:SerializedName("mediaType")
	val mediaType: String? = null
)
