package ru.bmstu.rk9.rdo.lib;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.TreeSet;

import ru.bmstu.rk9.rdo.lib.Database.TypeSize;
import ru.bmstu.rk9.rdo.lib.json.JSONArray;
import ru.bmstu.rk9.rdo.lib.json.JSONObject;

public class LegacyTracer extends Tracer
{
	LegacyTracer()
	{
		super();

		initializeTypes();
		initializeActivities();
	}

	private final HashMap<Integer, HashMap<Integer, Integer>> legacyResourceIds =
		new HashMap<Integer, HashMap<Integer, Integer>>();
	private final TreeSet<Integer> takenResourceIds =
		new TreeSet<Integer>();
	private final PriorityQueue<Integer> vacantActionNumbers =
		new PriorityQueue<Integer>();
	private final HashMap<Integer, HashMap<
		Integer, HashMap<Integer, Integer>>> legacyActionNumbers=
			new HashMap<Integer, HashMap<Integer, HashMap<Integer, Integer>>>();

	static private final String delimiter = " ";

	private RealFormatter realFormatter = new RealFormatter();

	private class RealFormatter
	{
		public String format(double number)
		{
			String output;
			if (number < 1000000)
			{
				BigDecimal raw = BigDecimal.valueOf(number);
				BigDecimal result =
					raw
						.round(new MathContext(6, RoundingMode.HALF_UP))
						.stripTrailingZeros();
				output = result.toPlainString();
			}
			else
			{
				DecimalFormatSymbols symbols =
					DecimalFormatSymbols.getInstance();
				symbols.setExponentSeparator("e+");
				DecimalFormat formatter = new DecimalFormat("0.#####E00");
				formatter.setDecimalFormatSymbols(symbols);
				output = formatter.format(number);
			}
			return output;
		}
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                                   GENERAL                                 /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	private boolean simulationStarted = false;
	private boolean dptSearchJustStarted = false;
	private boolean dptSearchJustFinished = false;
	private double dptSearchTime = 0;

	@Override
	protected final TraceOutput parseSerializedData(final Database.Entry entry)
	{
		if (dptSearchJustStarted)
		{
			addLegacySearchEntriesOnStart();
			dptSearchJustStarted = false;
		}

		if (dptSearchJustFinished)
		{
			addLegacySearchEntriesOnFinish(dptSearchTime);
			dptSearchJustFinished = false;
		}

		final Database.EntryType type =
			Database.EntryType.values()[entry.header.get(
				TypeSize.Internal.ENTRY_TYPE_OFFSET)];

		switch(type)
		{
		case SYSTEM:
			return parseSystemEntry(entry);
		case RESOURCE:
			return parseResourceEntry(entry);
		case PATTERN:
			return parsePatternEntry(entry);
		case SEARCH:
			return parseSearchEntry(entry);
		case RESULT:
			return parseResultEntry(entry);
		default:
			return null;
		}
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                          PARSING SYSTEM ENTRIES                           /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	@Override
	protected TraceOutput parseSystemEntry(final Database.Entry entry)
	{
		final ByteBuffer header = prepareBufferForReading(entry.header);

		final TraceType traceType = TraceType.SYSTEM;

		skipPart(header, TypeSize.BYTE);
		final double time = header.getDouble();
		final Database.SystemEntryType type =
			Database.SystemEntryType.values()[header.get()];

		int legacyCode;

		switch (type)
		{
		case TRACE_START:
			legacyCode = 1;
			break;
		case SIM_START:
			legacyCode = 3;
			simulationStarted = true;
			break;
		default:
			legacyCode = 2;
			break;
		}

		final String headerLine =
			new RDOLibStringJoiner(delimiter)
			.add(traceType.toString())
			.add(realFormatter.format((time)))
			.add(legacyCode)
			.getString();

		return new TraceOutput(traceType, headerLine);
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                          PARSING RESOURCE ENTRIES                         /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	@Override
	protected final TraceOutput parseResourceEntry(final Database.Entry entry)
	{
		final ByteBuffer header = prepareBufferForReading(entry.header);
		final ByteBuffer data = prepareBufferForReading(entry.data);

		skipPart(header, TypeSize.BYTE);
		final double time = header.getDouble();
		final TraceType traceType;
		final Database.ResourceEntryType entryType =
			Database.ResourceEntryType.values()[header.get()];
		final int typeNum = header.getInt();
		final int resNum = header.getInt();

		int legacyId;
		switch(entryType)
		{
		case CREATED:
			traceType = simulationStarted ?
				TraceType.RESOURCE_CREATE : TraceType.RESOURCE_KEEP;

			if (legacyResourceIds.get(typeNum).get(resNum) == null)
				legacyId = getNewResourceId(typeNum, resNum);
			else
				legacyId = legacyResourceIds.get(typeNum).get(resNum);

			break;
		case ERASED:
			traceType = TraceType.RESOURCE_ERASE;
			legacyId = legacyResourceIds.get(typeNum).get(resNum);
			freeResourceId(typeNum, resNum);
			break;
		case ALTERED:
			traceType = TraceType.RESOURCE_KEEP;
			legacyId = legacyResourceIds.get(typeNum).get(resNum);
			break;
		case SEARCH:
		case SOLUTION:
			traceType = TraceType.SEARCH_RESOURCE_KEEP;
			legacyId = legacyResourceIds.get(typeNum).get(resNum);
			break;
		default:
			return null;
		}

		final String headerLine =
			new RDOLibStringJoiner(delimiter)
			.add(traceType.toString())
			.add(realFormatter.format((time)))
			.add(typeNum + 1)
			.add(legacyId)
			.getString();

		final ResourceTypeInfo typeInfo = resourceTypesInfo.get(typeNum);

		return
			new TraceOutput(
				traceType,
				new RDOLibStringJoiner(delimiter)
					.add(headerLine)
					.add(parseResourceParameters(data, typeInfo))
					.getString()
			);
	}

	@Override
	protected final String parseResourceParameters(
		final ByteBuffer data,
		final ResourceTypeInfo typeInfo
	)
	{
		final RDOLibStringJoiner stringJoiner =
			new RDOLibStringJoiner(delimiter);

		for (int paramNum = 0; paramNum < typeInfo.numberOfParameters; paramNum++)
		{
			//TODO trace arrays when they are implemented
			switch(typeInfo.paramTypes.get(paramNum).type)
			{
			case INTEGER:
				stringJoiner.add(data.getInt());
				break;
			case REAL:
				stringJoiner.add(realFormatter.format(data.getDouble()));
				break;
			case BOOLEAN:
				stringJoiner.add(legacyBooleanString(data.get() != 0));
				break;
			case ENUM:
				stringJoiner.add(data.getShort());
				break;
			case STRING:
				final int index = typeInfo.indexList.get(paramNum);
				final int stringPosition = data.getInt(
					typeInfo.finalOffset + (index - 1) * TypeSize.RDO.INTEGER);
				final int length = data.getInt(stringPosition);

				byte rawString[] = new byte[length];
				for (int i = 0; i < length; i++)
				{
					rawString[i] = data.get(
						stringPosition + TypeSize.RDO.INTEGER + i);
				}
				stringJoiner.add(new String(rawString, StandardCharsets.UTF_8));
				break;
			default:
				return null;
			}
		}
		return stringJoiner.getString();
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                          PARSING PATTERN ENTRIES                          /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	@Override
	protected final TraceOutput parsePatternEntry(final Database.Entry entry)
	{
		final ByteBuffer header = prepareBufferForReading(entry.header);
		final ByteBuffer data = prepareBufferForReading(entry.data);

		skipPart(header, TypeSize.BYTE);
		final double time = header.getDouble();
		final TraceType traceType;

		final Database.PatternType entryType =
			Database.PatternType.values()[header.get()];
		switch(entryType)
		{
		case EVENT:
			traceType = TraceType.EVENT;
			break;
		case RULE:
			traceType = TraceType.RULE;
			break;
		case OPERATION_BEGIN:
			traceType = TraceType.OPERATION_BEGIN;
			break;
		case OPERATION_END:
			traceType = TraceType.OPERATION_END;
			break;
		default:
			return null;
		}

		return
			new TraceOutput(
				traceType,
				new RDOLibStringJoiner(delimiter)
					.add(traceType.toString())
					.add(realFormatter.format(time))
					.add(parsePatternData(data, traceType))
					.getString()
			);
	}

	@Override
	protected final String parsePatternData(
		final ByteBuffer data,
		final TraceType patternType
	)
	{
		final RDOLibStringJoiner stringJoiner =
			new RDOLibStringJoiner(delimiter);

		int patternNumber;

		switch(patternType)
		{
		case EVENT:
		{
			patternNumber = data.getInt();
			skipPart(data, TypeSize.INTEGER);
			stringJoiner
				.add(patternNumber + 1)
				.add(patternNumber + 1);
			break;
		}
		case RULE:
		{
			int dptNumber = data.getInt();
			int activityNumber = data.getInt();
			skipPart(data, TypeSize.INTEGER);
			patternNumber = decisionPointsInfo.get(dptNumber)
				.activitiesInfo.get(activityNumber).patternNumber;
			stringJoiner
				.add(1)
				.add(activityNumber + 1)
				.add(patternNumber + 1);
			break;
		}
		case OPERATION_BEGIN:
		{
			int dptNumber = data.getInt();
			int activityNumber = data.getInt();
			int actionNumber = data.getInt();

			HashMap<Integer, Integer> activityActions =
				legacyActionNumbers
				.get(dptNumber)
				.get(activityNumber);

			int legacyNumber;
			if (vacantActionNumbers.isEmpty())
				legacyNumber = activityActions.size();
			else
				legacyNumber = vacantActionNumbers.poll();

			activityActions.put(actionNumber, legacyNumber);

			patternNumber = decisionPointsInfo.get(dptNumber)
					.activitiesInfo.get(activityNumber).patternNumber;
			stringJoiner
				.add(legacyNumber + 1)
				.add(activityNumber + 1)
				.add(patternNumber + 1);
			break;
		}
		case OPERATION_END:
		{
			int dptNumber = data.getInt();
			int activityNumber = data.getInt();
			int actionNumber = data.getInt();

			HashMap<Integer, Integer> activityActions =
					legacyActionNumbers
					.get(dptNumber)
					.get(activityNumber);
			int legacyNumber = activityActions.remove(actionNumber);
			vacantActionNumbers.add(legacyNumber);

			patternNumber = decisionPointsInfo.get(dptNumber)
					.activitiesInfo.get(activityNumber).patternNumber;
			stringJoiner
				.add(legacyNumber + 1)
				.add(activityNumber + 1)
				.add(patternNumber + 1);
		}
			break;
		default:
			return null;
		}

		int numberOfRelevantResources = data.getInt();
		stringJoiner.add(numberOfRelevantResources).add("");
		for(int num = 0; num < numberOfRelevantResources; num++)
		{
			final int typeNum =
				patternsInfo.get(patternNumber).relResTypes.get(num);
			final int resNum = data.getInt();
			if (legacyResourceIds.get(typeNum).get(resNum) == null)
			{
				stringJoiner.add(getNewResourceId(typeNum, resNum));
			}
			else
			{
				final int legacyId =
					legacyResourceIds.get(typeNum).get(resNum);
				stringJoiner.add(legacyId);
			}
		}

		return stringJoiner.getString();
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                              SEARCH ENTRIES                               /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	//TODO in old RDO traceStart and Simulation are simultaneous
	//when we deal with DecisionPointSearch, so no initial resource
	//values are not printed.
	//How to check that?

	@Override
	protected TraceOutput parseSearchEntry(final Database.Entry entry)
	{
		final ByteBuffer header = prepareBufferForReading(entry.header);
		final ByteBuffer data = prepareBufferForReading(entry.data);

		final RDOLibStringJoiner stringJoiner =
			new RDOLibStringJoiner(delimiter);

		final TraceType traceType;
		skipPart(header, TypeSize.BYTE);
		final Database.SearchEntryType entryType =
				Database.SearchEntryType.values()[header.get()];

		switch(entryType)
		{
		case BEGIN:
		{
			System.out.println("begin");
			dptSearchJustStarted = true;
			traceType = TraceType.SEARCH_BEGIN;
			final double time = data.getDouble();
			dptSearchTime = time;
			final int number = data.getInt();
			currentDptNumber = number;
			skipPart(data, TypeSize.INTEGER);
			stringJoiner
				.add(traceType.toString())
				.add(realFormatter.format(time))
				.add(number + 1);
			break;
		}
		case END:
		{
			System.out.println("end");
			dptSearchJustFinished = true;
			currentDptNumber = -1;
			final DecisionPointSearch.StopCode endStatus =
				DecisionPointSearch.StopCode.values()[data.get()];
			switch(endStatus)
			{
			case ABORTED:
				traceType = TraceType.SEARCH_END_ABORTED;
				break;
			case CONDITION:
				traceType = TraceType.SEARCH_END_CONDITION;
				break;
			case SUCCESS:
				traceType = TraceType.SEARCH_END_SUCCESS;
				break;
			case FAIL:
				traceType = TraceType.SEARCH_END_FAIL;
				break;
			default:
				//TODO throw exception
				return null;
			}

			final double time = data.getDouble();
			final long timeMillis = data.getLong();
			final long mem = data.getLong();
			final double finalCost = data.getDouble();
			final int totalOpened = data.getInt();
			final int totalNodes = data.getInt();
			final int totalAdded = data.getInt();
			final int totalSpawned = data.getInt();
			stringJoiner
				.add(traceType.toString())
				.add(realFormatter.format(time))
				.add(timeMillis)
				.add(mem)
				.add(realFormatter.format(finalCost))
				.add(totalOpened)
				.add(totalNodes)
				.add(totalAdded)
				.add(totalSpawned);
			break;
		}
		case OPEN:
		{
			System.out.println("open");
			traceType = TraceType.SEARCH_OPEN;
			final int currentNumber = data.getInt();
			final int parentNumber = data.getInt();
			final double g = data.getDouble();
			final double h = data.getDouble();
			stringJoiner
				.add("SO")
				.add(currentNumber + 1)
				.add(parentNumber + 1)
				.add(realFormatter.format(g))
				.add(realFormatter.format(g + h));
			break;
		}
		case SPAWN:
		{
			System.out.println("spawn");
			final DecisionPointSearch.SpawnStatus spawnStatus =
					DecisionPointSearch.SpawnStatus.values()[data.get()];
			switch(spawnStatus)
			{
			case NEW:
				traceType = TraceType.SEARCH_SPAWN_NEW;
				break;
			case WORSE:
				traceType = TraceType.SEARCH_SPAWN_WORSE;
				break;
			case BETTER:
				traceType = TraceType.SEARCH_SPAWN_BETTER;
				break;
			default:
				//TODO throw exception
				return null;
			}
			final int childNumber = data.getInt();
			final int parentNumber = data.getInt();
			final double g = data.getDouble();
			final double h = data.getDouble();
			final int ruleNumber = data.getInt();
			final int patternNumber = decisionPointsInfo.get(currentDptNumber)
				.activitiesInfo.get(ruleNumber).patternNumber;
			final double ruleCost = data.getDouble();
			final int numberOfRelevantResources =
				patternsInfo.get(patternNumber).relResTypes.size();

			stringJoiner
				.add(traceType.toString())
				.add(childNumber + 1)
				.add(parentNumber + 1)
				.add(realFormatter.format(g))
				.add(realFormatter.format(g + h))
				.add(ruleNumber + 1)
				.add(patternNumber + 1)
				.add(realFormatter.format(ruleCost))
				.add(numberOfRelevantResources)
				.add("");

			for (int num = 0; num < numberOfRelevantResources; num++)
			{
				final int typeNum =
					patternsInfo.get(patternNumber).relResTypes.get(num);
				final int resNum = data.getInt();
				final int legacyNum =
					legacyResourceIds.get(typeNum).get(resNum);
				stringJoiner.add(legacyNum);
			}
			break;
		}
		case DECISION:
		{
			System.out.println("decision");
			traceType = TraceType.SEARCH_DECISION;
			final int number = data.getInt();
			final int activityNumber = data.getInt();
			stringJoiner
				.add("SD")
				.add(number)
				.add(activityNumber);
			break;
		}
		default:
			return null;
		}

		return
			new TraceOutput(
				traceType,
				stringJoiner.getString()
			);
	}

	private final void addLegacySearchEntriesOnStart()
	{
		traceList.add(
			new TraceOutput(
				TraceType.SEARCH_SPAWN_NEW,
				new RDOLibStringJoiner(delimiter)
					.add("STN")
					.add(1)
					.add(0)
					.add(0)
					.add(0)
					.add(-1)
					.add(-1)
					.add(0)
					.add(0)
					.getString()
			)
		);

		traceList.add(
			new TraceOutput(
				TraceType.SEARCH_OPEN,
				new RDOLibStringJoiner(delimiter)
					.add("SO")
					.add(1)
					.add(0)
					.add(0)
					.add(0)
					.getString()
			)
		);
	}

	private final void addLegacySearchEntriesOnFinish(double time)
	{
		traceList.add(
				new TraceOutput(
					TraceType.SYSTEM,
					new RDOLibStringJoiner(delimiter)
						.add(TraceType.SYSTEM.toString())
						.add(realFormatter.format(time))
						.add(4)
						.getString()
				)
			);
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                           PARSING RESULT ENTRIES                          /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	@Override
	protected final TraceOutput parseResultEntry(final Database.Entry entry)
	{
		final ByteBuffer header = prepareBufferForReading(entry.header);
		final ByteBuffer data = prepareBufferForReading(entry.data);

		skipPart(header, TypeSize.BYTE);
		final double time = header.getDouble();
		final int resultNum = header.getInt();

		final ResultInfo resultInfo = resultsInfo.get(resultNum);

		return
			new TraceOutput(
			TraceType.RESULT,
			new RDOLibStringJoiner(delimiter)
				.add(TraceType.RESULT.toString())
				.add(realFormatter.format(time))
				.add(resultNum + 1)
				.add("")
				.add(parseResultParameter(data, resultInfo))
				.getString()
			);
	}

	@Override
	protected final String parseResultParameter(
		final ByteBuffer data,
		final ResultInfo resultInfo
	)
	{
		switch(resultInfo.valueType)
		{
		case INTEGER:
			return String.valueOf(data.getInt());
		case REAL:
			return realFormatter.format(data.getDouble());
		case BOOLEAN:
			return legacyBooleanString(data.get() != 0);
		case ENUM:
			return String.valueOf(data.getShort());
		case STRING:
			final ByteArrayOutputStream rawString = new ByteArrayOutputStream();
			while (data.hasRemaining())
			{
				rawString.write(data.get());
			}
			return rawString.toString();
		default:
			break;
		}

		return null;
	}

  /*――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――/
 /                               HELPER METHODS                              /
/――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――*/

	private final void initializeTypes()
	{
		final JSONArray resourceTypes =
				Simulator
				.getDatabase()
				.getModelStructure()
				.getJSONArray("resource_types");

		for (int num = 0; num < resourceTypes.length(); num++)
		{
			legacyResourceIds.put(
				num,
				new HashMap<Integer, Integer>()
			);
		}
	}

	private final void initializeActivities()
	{
		JSONArray decisionPoints =
			Simulator
			.getDatabase()
			.getModelStructure()
			.getJSONArray("decision_points");

		for(int dptNum = 0; dptNum < decisionPoints.length(); dptNum++)
		{
			JSONObject decisionPoint = decisionPoints.getJSONObject(dptNum);
			String type = decisionPoint.getString("type");
			switch(type)
			{
				case "some":
				case "prior":
					HashMap<Integer, HashMap<Integer, Integer>> activities =
						new HashMap<Integer, HashMap<Integer, Integer>>();
					JSONArray jActivities =
						decisionPoint.getJSONArray("activities");
					for(int actNum = 0; actNum < jActivities.length(); actNum++)
					{
						HashMap<Integer, Integer> activity =
							new HashMap<Integer, Integer>();
						activities.put(actNum, activity);
					}
					legacyActionNumbers.put(dptNum, activities);
					break;
				default:
					break;
			}
		}
	}

	private final void freeResourceId(int typeNum, int resNum)
	{
		int legacyId = legacyResourceIds.get(typeNum).get(resNum);
		legacyResourceIds.get(typeNum).remove(resNum);
		takenResourceIds.remove(legacyId);
	}

	private final int getNewResourceId(int typeNum, int resNum)
	{
		int current;
		int legacyId = 1;
		Iterator<Integer> it = takenResourceIds.iterator();
		while (it.hasNext())
		{
			current = it.next();
			if (current != legacyId)
				break;
			legacyId++;
		}
		legacyResourceIds.get(typeNum).put(resNum, legacyId);
		takenResourceIds.add(legacyId);
		return legacyId;
	}

	private final String legacyBooleanString(boolean value)
	{
		return value ? "TRUE" : "FALSE";
	}
}
