package com.combatbreakdown.logic;

import com.combatbreakdown.model.TargetSummary;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

public final class HistorySerializer
{
	private final Gson gson;

	public HistorySerializer(Gson gson)
	{
		this.gson = gson;
	}

	public String serialize(List<TargetSummary> summaries)
	{
		if (summaries == null || summaries.isEmpty())
		{
			return "[]";
		}
		return gson.toJson(summaries);
	}

	public List<TargetSummary> deserialize(@Nullable String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		try
		{
			TargetSummary[] parsed = gson.fromJson(json, TargetSummary[].class);
			if (parsed == null || parsed.length == 0)
			{
				return Collections.emptyList();
			}
			List<TargetSummary> clean = new ArrayList<>(parsed.length);
			for (TargetSummary s : parsed)
			{
				if (s != null && s.getNpcName() != null && !s.getNpcName().trim().isEmpty() && s.getKills() > 0)
				{
					s.setNpcName(s.getNpcName().trim());
					clean.add(s);
				}
			}
			return clean;
		}
		catch (JsonParseException ex)
		{
			return Collections.emptyList();
		}
	}
}
